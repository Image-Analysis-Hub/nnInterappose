/*-
 * #%L
 * Use nnInteractive in Fiji
 * %%
 * Copyright (C) 2026 DSCB
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the DSCB nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

/*
 */
package fiji.plugin.nninterappose;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.WindowConstants;

import org.apache.commons.io.IOUtils;
import org.apposed.appose.Appose;
import org.apposed.appose.BuildException;
import org.apposed.appose.Environment;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service;
import org.apposed.appose.Service.Task;
import org.apposed.appose.Service.TaskStatus;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.ChannelSplitter;
import ij.plugin.PlugIn;
import ij.plugin.RGBStackMerge;
import ij.plugin.frame.RoiManager;
import ij.process.ImageConverter;
import ij.process.LUT;
import net.imagej.ImgPlus;
import net.imglib2.appose.NDArrays;
import net.imglib2.appose.ShmImg;
import net.imglib2.img.ImagePlusAdapter;
import net.imglib2.img.Img;
import net.imglib2.img.ImgView;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;

public class Interact implements PlugIn
{
	private RoiManager rm = null;  // handle ROIs interaction: inputs and results
	private ImagePlus imp = null; // img on which we are working
	private CompositeImage merged = null; // Results image
	private int nlabels = 0; // current number of labels created
	private boolean all_for_one = true; // define one object by ROI or all for one
	
	private Service nnservice = null; // running python service
	final String run_script = getScript( this.getClass().getResource("run_session.py" ) );
	
	
	/**
	 * Create the parameter interface
	 */
	public void main_gui()
	{
		JFrame frame = new JFrame("nnInter-Appose");
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.setSize(800, 250);
		
		// Stop the nnInteractive python task
		JButton btnStop = new JButton("STOP");
		btnStop.addActionListener( e -> 
		{
			stopService();
			frame.dispose();
		});
		
		// Add positive/negative ROIs
		JButton btnAddPos = new JButton( "Add positive ROI (or press '1')" );
		btnAddPos.addActionListener( e -> 
		{
			addRoi( true );
		});
		btnAddPos.setToolTipText("Add current selection in the image as a positive seed for nnInteractive");
		
		JButton btnAddNeg = new JButton( "Add negative ROI (or press '2')" );
		btnAddNeg.addActionListener( e -> 
		{
			addRoi( false );
		});
		btnAddNeg.setToolTipText("Add current selection in the image as a negative seed for nnInteractive");
		
		// Choose mode: one ROI to create one object or multiple ROIs to refine one object
		JComboBox<String> mode_choice = new JComboBox<String>();
        mode_choice.addItem("All ROIs define one object");
        mode_choice.addItem("One ROI by object");
        
        mode_choice.addItemListener(new ItemListener() 
        {
            @Override
            public void itemStateChanged(ItemEvent e) 
            {
                if (e.getStateChange() == ItemEvent.SELECTED) 
                {
                    String selected = (String) e.getItem();
                   //System.out.println("Changed to: " + selected);
                    if (selected.equals("One ROI by object"))
                    {
                    	all_for_one = false;
                    	btnAddNeg.setEnabled(false);
                    }
                    else 
                    {
                    	all_for_one = true;
                    	btnAddNeg.setEnabled(true);
                    }
                }
            }
        });
        mode_choice.setToolTipText( "Choose which mode of segmentation to use: each ROI defines a new object or all ROIs define more precisely one object" );
        mode_choice.setBackground( new Color(205, 229, 252) );
		
        // send roi to nn
		JButton btnSendRoi = new JButton("Segment from ROIs (or press '0')");
		btnSendRoi.addActionListener( e -> 
		{
			sendRois();
		});
		
		JLabel removeLab = new JLabel( "Ctrl+Left click on a segmented object to remove it" );
		removeLab.setToolTipText( "To remove a wrong segmentation, control+left click on the object" );
				
		frame.setLayout( new GridBagLayout() );
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.ipadx = 15;
		gbc.ipady = 15;
		gbc.insets = new Insets( 2, 2, 2, 2 ); // space between components
				
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.gridwidth = 1;
		frame.add( btnStop );
		gbc.gridx = 1;
		frame.add( Box.createGlue(), gbc);
		gbc.gridx = 2;
		frame.add( Box.createGlue(), gbc);
		gbc.gridx = 0;
		gbc.gridy = 1;
		frame.add( btnAddPos, gbc );
		gbc.gridx = 1;
		frame.add( btnAddNeg,gbc );
		gbc.gridx = 2;
		frame.add( Box.createGlue(),gbc);
		gbc.gridy = 2;
		gbc.gridx = 0;
		frame.add( mode_choice,gbc );
		gbc.gridx = 1;
		frame.add( btnSendRoi,gbc );
		gbc.gridx = 2;
		frame.add( Box.createGlue(),gbc);
		gbc.gridx = 0;
		gbc.gridy = 3;
		gbc.gridwidth = 3;          // span 3 columns
		gbc.fill = GridBagConstraints.HORIZONTAL; // stretch horizontally
		frame.add( removeLab, gbc);
	
		frame.setLocationRelativeTo(null);
		frame.pack();
		frame.setVisible(true);
	}
	
	public void addShortcuts()
	{
		ImageWindow w = merged.getWindow();
		 
		if (w!=null)
		{
			// Mouse shortcuts: under a click
			MouseListener ml = new MouseListener() 
			{
				
				@Override
				public void mouseReleased(MouseEvent e) {}
				
				@Override
				public void mousePressed(MouseEvent e) {}
				
				@Override
				public void mouseExited(MouseEvent e) {}
				
				@Override
				public void mouseEntered(MouseEvent e) {}
				
				@Override
				public void mouseClicked(MouseEvent e) 
				{
					// Control + left click
					if ( e.getButton() == e.BUTTON1 )
					{
						if ( e.getModifiersEx() == e.CTRL_DOWN_MASK )
						{
							int label = getValueUnderClick( e );
							removeLabel( label );
							System.out.println("Removed object "+label);
						}
					}
				//System.out.println(e.getButton());
				//System.out.println(e.getModifiersEx());
				
				//System.out.println(e.);
					
				}
			};
			
			// Keyboard shortcuts
			KeyListener kl =  new KeyListener()
			{
						
				@Override
				public void keyReleased( KeyEvent e )
				{
					switch (e.getKeyChar() )
					{
					case '0':
						sendRois();
						break;
					case '1':
						//System.out.println("1 pressed");
						addRoi(true);
						break;
					case '2':
						//System.out.println("2 pressed");
						addRoi(false);
						break;
					default:
						break;
					}
				}

				@Override
				public void keyTyped(KeyEvent e) {	
				}

				@Override
				public void keyPressed(KeyEvent e) {	
				}
				
				
			};
			w.addKeyListener( kl );
			w.getCanvas().addKeyListener( kl );
			w.addMouseListener( ml );
			w.getCanvas().addMouseListener(ml);
		}
	}
	
	public int getValueUnderClick( MouseEvent e )
	{
		ImageCanvas canvas = merged.getCanvas();
        int x = canvas.offScreenX(e.getX());
        int y = canvas.offScreenY(e.getY());
		int index = merged.getStackIndex( 2, merged.getSlice(), 1);
		int label = (int) merged.getStack().getProcessor(index).getValue(x, y);
		return label;	
	}
	
	/**
	 * Add a ROI to the ROI Manager, naming it positive or negative for nninteractions
	 * param positive
	 */
	public void addRoi( boolean positive )
	{
		Roi roi = merged.getRoi();
		if ( roi == null )
		{
			IJ.log( "No active ROI to add to Manager" );
			return;
		}
		roi.setPosition( 1, merged.getSlice(), 1 );
		if ( positive )
			roi.setName("positive");
		else
			roi.setName("negative");
		rm.addRoi(roi);
	}
	
	/**
	 * Stop the currently active nnInteractive task: send it stop signal
	 */
	public void stopService()
	{
		if ( nnservice != null )
		{
			IJ.log( "Close python" );
			nnservice.close();
		}
	}
	
	/**
	 * Remove the given label: replace by 0
	 * param label
	 */
	public void removeLabel( int label )
	{
		// Set the labels channel as active channel
		merged.setC( 2 );
		IJ.run(merged, "Macro...", "code=v=v-v*(v=="+label+") stack");
		//imp.updateAndDraw();
	}
	
	/**
	 * Get ROIs from ROiManager and send them to the python process to nnInteractive
	 */
	public < T extends RealType< T > & NativeType< T > >  void sendRois()
	{
		if ( nnservice == null )
		{
			IJ.error("No currently active session. Wait for initialization to be done or relaunch the plugin");
			return;
		}
		
		// Check if has been initialised else do it now
		if ( merged == null )
			prepareResultImage();

		// If in mode all_for_one, all ROIs will be deleted, so do it only once. If in mode one by one, do each ROI
		while ( rm.getCount() > 0 )
		{

			try 
			{
				final Map< String, Object > nninputs = new HashMap<>();  // could keep in memory last set of ROIs so that can reset last segmentation
				final List<List<Integer>> bboxes = new ArrayList<>();   // Type of nnInteraction: bounnding box
				final List<List<Integer>> points = new ArrayList<>();   // Type of nnInteraction: seeds point
				final List<List<List<Integer>>> scribbles = new ArrayList<>();  // Type of nnInteraction: scribbles
				final List<List<Integer>> scrib_prop = new ArrayList<>(); // Thickness of the scribbles drawings, and positive or negative interaction

				boolean first = true;
				// Get all possible ROIs
				while ( (all_for_one && (rm.getCount() > 0)) || (!all_for_one && first) )
				{
					Roi roi = rm.getRoi(0);
					// Handle different ROI possibilities
					int type = roi.getType();
					int z = roi.getZPosition() - 1; // getZPosition doesn't work for points ROI. -1 for starting at 0
					int positive = roi.getName().equals("negative")?0:1; // Roi is a positive seed 
					switch (type) 
					{
					case Roi.RECTANGLE: 
						//System.out.println("Rectangle ROI");
						Rectangle rect = roi.getBounds();
						bboxes.add( Arrays.asList( z, rect.y, rect.x, rect.y+rect.height, rect.x+rect.width, positive) );
						break;  
					case Roi.OVAL:    
						IJ.log("Oval ROIs not handled");
						break; 
					case Roi.POLYGON:    
						IJ.log("Polygon ROis not handled yet. Fill an issue: https://github.com/Image-Analysis-Hub/nnInterappose/issues/new to ask for it to be added in priority");
						break;  
					case Roi.FREEROI:    
						IJ.log("Free ROIs not handled yet. Fill an issue: https://github.com/Image-Analysis-Hub/nnInterappose/issues/new to ask for it to be added in priority");
						break;
					case Roi.TRACED_ROI: break;
					case Roi.LINE:    
					case Roi.POLYLINE:  
					case Roi.FREELINE:   
						Point[] line_pts = roi.getContainedPoints();		
						int thickness = (int) roi.getStrokeWidth();
						List<List<Integer>> new_scribble = new ArrayList<>();
						for ( int j=0; j<line_pts.length; j++ )
						{
							new_scribble.add( Arrays.asList( z, line_pts[j].y, line_pts[j].x ) );
						}
						scribbles.add( new_scribble );
						scrib_prop.add( Arrays.asList(thickness, positive) );
						break;  
					case Roi.ANGLE:      
						IJ.log("Angle ROIs are not handled");
						break;
					case Roi.COMPOSITE: 
						IJ.log("ROI type is not handled");
						break;
					case Roi.POINT: 
						Point[] pts = roi.getContainedPoints();
						for ( int j=0; j<pts.length; j++ )
							points.add( Arrays.asList( z, pts[j].y, pts[j].x, positive) );
						break;  // 10
					default:
						IJ.log("Unrecognized ROI");
						break;
					}
					rm.delete(0);
					first = false;
				}
				// Put in inputs to send to appose shared memory
				nninputs.put( "bboxs", bboxes );
				nninputs.put( "points", points );
				nninputs.put( "scribbles", scribbles );
				nninputs.put( "scribbles_properties", scrib_prop );

				// Go launch task of segmentation with the ROIs as seeds
				Task task = nnservice.task( run_script, nninputs );
				task.listen( e -> {
					if (e.message != null) 
					{ 
						IJ.log( e.message );
					}
				});
				task.waitFor();
				if ( task.status != TaskStatus.COMPLETE )
					throw new RuntimeException( "Python script failed with error: " + task.error );
				addOutputToLabels( task );	
			}
			catch ( Exception e)
			{
				IJ.error( "Something went wrong: " + e );
			}
		}
	}
	
	/**
	 * Creates the image where the results will be displayed in.
	 * Makes a Composite with the raw image
	 */
	public void prepareResultImage()
	{
		int nslices = imp.getNSlices();
		// switch axes in the Composite image, so that it is 3D
		if ( nslices<= 1)
		{
			nslices = imp.getNFrames();
		}
	
		ImagePlus labels = IJ.createImage(
			    "Labels",               // title
			    "32-bit black",         // type + fill
			    imp.getWidth(),        // width
			    imp.getHeight(),       // height
			    1,
			    nslices,      // number of slices
			    1
		);
		useGlasbeyDarkLUT( labels );
		
		// Convert raw input image to 32-bits so that it can be overlaid
		ImageConverter ic = new ImageConverter(imp);
		ic.convertToGray32();
		ImagePlus[] channels = new ImagePlus[7]; // all possible channels
		if ( imp.getNChannels() > 1 )
		{
			int chan = imp.getC();
			channels[0] = new ImagePlus("Channel", new ChannelSplitter().getChannel( imp, chan ) );
		}
		else
		{
			channels[0] = imp;
		}
		channels[0].setDimensions(1, nslices, 1); // ensure it's the correct dimensions
		channels[1] = labels;

		// Merge create composite
		merged = (CompositeImage) RGBStackMerge.mergeChannels(channels, true);
		merged.show();
		merged.getProcessor().resetMinAndMax();
		
		transferCalibration( imp, merged );
	}
	
	/**
	 * Get the outputs in the shared memory and add it to the labels image
	 * param task
	 */
	public < T extends RealType< T > & NativeType< T > > void addOutputToLabels( Task task )
	{
		boolean as_contour = false; // first version with contour -> Rois
		if ( as_contour )
		{
			task.outputs.get("coordinates");
			Map<String, Object> rois = (Map<String, Object>) task.outputs.get( "coordinates" );
			boolean zaxis = imp.getNSlices() > 1; // work in Z axis, else in T axis
			for ( Map.Entry<String, Object> slice_roi: rois.entrySet() )
			{
				List<List<Double>> contour = (List<List<Double>>) slice_roi.getValue();
				System.out.println("Key: " + slice_roi.getKey() + ", Value: " + contour.get(0));
				Roi result = contourToRoi( contour );
				int slice = Integer.parseInt( slice_roi.getKey() ); 
				if ( zaxis )
				{
					result.setPosition( 1, slice+1, 1 ); // Starts at 1	
				}
				else
				{
					result.setPosition( 1, 1, slice+1 ); // Starts at 1				
				}
				result.setImage( imp ); 
				rm.addRoi( result );
				imp.setRoi( result );
			}
		}
		// versions with binary -> labels
		else
		{
			final NDArray maskArr = ( NDArray ) task.outputs.get( "binary_stack" );
			Img< T > output = new ShmImg<>( maskArr );
			nlabels ++;
			//System.out.println("Nlabels: "+nlabels);
			LoopBuilder.setImages(output)
		    .forEachPixel(p -> p.setReal(p.getRealDouble() * nlabels));
			output =  ImgView.wrap( Views.dropSingletonDimensions(output) );
			//System.out.println(output.numDimensions());
			ImagePlus labels = new ImagePlus("Labels", new ChannelSplitter().getChannel(merged, 2 ));
			Img<T> imgLabels = ImageJFunctions.wrap( labels );
	
			LoopBuilder.setImages( output, imgLabels )
			    .forEachPixel((s, d) -> d.setReal(d.getRealDouble() + s.getRealDouble()));

			// Refresh display
			merged.setC(2);
			//merged.setStack( labels.getStack() ); // change everything, not just the current channel
			// Replace the processor for each slice
			int cur_slice = merged.getZ();
			for (int z = 1; z <= merged.getNSlices(); z++) 
			{
			    merged.setZ(z);
			    merged.setProcessor( labels.getStack().getProcessor(z) );
			}
			// Put back to current slice, and reset contrast
			merged.setZ(cur_slice);
			merged.setDisplayRange(0, nlabels+1);
			merged.updateAndDraw();
		}
	}
	
	public static final void useGlasbeyDarkLUT( final ImagePlus imp )
	{
		final LUT lut = loadLutFromResource( "/glasbey_on_dark.lut" );
		useLUT( imp, lut );
	}
	
	private static LUT loadLutFromResource( final String resourcePath )
	{
		try (InputStream is = Interact.class.getResourceAsStream( resourcePath );
				BufferedReader reader = new BufferedReader( new InputStreamReader( is ) ))
		{

			if ( is == null )
			{
				IJ.error( "LUT resource not found: " + resourcePath );
				return null;
			}

			final byte[] reds = new byte[ 256 ];
			final byte[] greens = new byte[ 256 ];
			final byte[] blues = new byte[ 256 ];
			String line;
			int index = 0;

			while ( ( line = reader.readLine() ) != null && index < 256 )
			{
				line = line.trim();
				if ( line.isEmpty() )
					continue; // Skip empty lines

				// Split by whitespace
				final String[] parts = line.split( "\\s+" );
				if ( parts.length >= 3 )
				{
					reds[ index ] = ( byte ) Integer.parseInt( parts[ 0 ] );
					greens[ index ] = ( byte ) Integer.parseInt( parts[ 1 ] );
					blues[ index ] = ( byte ) Integer.parseInt( parts[ 2 ] );
					index++;
				}
			}

			if ( index != 256 )
			{
				IJ.error( "Invalid LUT file: expected 256 entries, found " + index );
				return null;
			}

			return new LUT( reds, greens, blues );
		}
		catch ( final IOException e )
		{
			IJ.error( "Failed to load LUT: " + e.getMessage() );
			return null;
		}
	}

	public static final void useLUT( final ImagePlus imp, final LUT lut )
	{
		imp.setLut( lut );
		imp.updateAndDraw();
	}

	/**
	 * Transfers the calibration of an {@link ImagePlus} to another one,
	 * generated from a capture of the first one.
	 *
	 * @param from
	 *            the imp to copy from.
	 * @param to
	 *            the imp to copy to.
	 */
	public static final void transferCalibration( final ImagePlus from, final ImagePlus to )
	{
		final Calibration fc = from.getCalibration();
		final Calibration tc = to.getCalibration();

		tc.setUnit( fc.getUnit() );
		tc.setTimeUnit( fc.getTimeUnit() );
		tc.frameInterval = fc.frameInterval;

		tc.pixelWidth = fc.pixelWidth;
		tc.pixelHeight = fc.pixelHeight;
		tc.pixelDepth = fc.pixelDepth;
	}
	
	/**
	 * Convert the python coordinates ListList to a Polygon ROI
	 * param contour
	 * return
	 */
	public static PolygonRoi contourToRoi( List<List<Double>> contour ) 
	{
	    int n = contour.size();
	    // Get the points in two arrays to create the polygon
	    float[] xPoints = new float[n];
	    float[] yPoints = new float[n];
	    for (int i = 0; i < n; i++) 
	    {
	    	//System.out.println(contour.get(i));
	        xPoints[i] = ((Number) contour.get(i).get(1)).floatValue();
	        yPoints[i] = ((Number) contour.get(i).get(0)).floatValue();
	    }

	    // Use Roi.POLYGON for a closed contour
	    return new PolygonRoi( xPoints, yPoints, n, Roi.POLYGON );
	}
	
	
	/**
	 * A utility to wrap an ImagePlus into an ImgPlus, without too many
	 * warnings. 
	 */
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	public static final < T > ImgPlus< T > rawWraps( final ImagePlus imp )
	{

		// If several channels, use only the current one
		if ( (imp.getNChannels() > 1)  )
		{
			int chan = imp.getC();
			ImagePlus rawip = new ImagePlus( "Raw", new ChannelSplitter().getChannel(imp, chan) );
			final ImgPlus< DoubleType > img = ImagePlusAdapter.wrapImgPlus( rawip );
			final ImgPlus raw = img;
			return raw;
		}
		// else, wrap it		
		final ImgPlus< DoubleType > img = ImagePlusAdapter.wrapImgPlus( imp );
		final ImgPlus raw = img;
		return raw;
	}
	
	/**
	 * Prepare the python environment and initialize nnInteractive to the active image
	 */
	public < T extends RealType< T > & NativeType< T > > void initialize()
	{
		
		final ImgPlus<T> img = rawWraps( imp );
		final String script = getScript( this.getClass().getResource("init_session.py" ) );
		/*
		 * Transfer the movie to shared object
		 */
		final Map< String, Object > inputs = new HashMap<>();
		inputs.put( "image", NDArrays.asNDArray( img ) );
		
		IJ.log( "Downloading/Installing the environment if necessary..." );
		String envName = "default"; // can be modified if need several types of environment
		
		Environment env = null;
		try {
			env = Appose // the builder
					.pixi( this.getClass().getResource("pixi.toml") ) // we chose pixi as the environment manager
					.subscribeProgress( this::showProgress ) // report progress visually
					.subscribeOutput( this::showProgress ) // report output visually
					.subscribeError( IJ::log ) // log problems
			        .environment( envName )  // choose env based on OS (to get cuda or not)
					.build();
		} 
		catch (BuildException e) 
		{
			IJ.error( "Error in creating/initializing the python environment: "+e.toString() );
			e.printStackTrace();
		} // create the environment
		hideProgress();

		
		/*
		 * Using this environment, we create a service that will run the Python
		 * script.
		 */
		nnservice = env.python();
		try
		{
			// Import all that depends on numpy for Windows
			nnservice.init("import os\n"
					+ "import numpy as np\n"
					+ "import nnInteractive\nimport skimage");
			
			//python.debug( msg -> show_messages( msg ) );
			
			Task task = nnservice.task( script, inputs );
			
			// Start the script, and return to Java immediately.
			IJ.log( "Starting nnInteractive task..." );
			 // Listen for events from Python
			task.listen( e -> {
				if (e.message != null) 
				{ 
					IJ.log( e.message );
				}

			} );
		    		    
		    task.start();
			task.waitFor();
			
			
			IJ.showStatus( "Annotation" );
			
			// Verify that it worked.
			if ( task.status != TaskStatus.COMPLETE )
				throw new RuntimeException( "Python script failed with error: " + task.error );
		}
		catch ( Exception e)
		{
			IJ.error( "" + e );
		}
	}
	
	/*
	 * The Python script.
	 * 
	 * This is the Python code that will be run by the service. It is loaded from an existing
	 * .py file, placed in the URL location */
	public static String getScript( URL python_script )
	{
		String script = "";
		try {
			final URL scriptFile = python_script;
			script = IOUtils.toString(scriptFile, StandardCharsets.UTF_8);
			
		} catch (final IOException e) {
			e.printStackTrace();
		}
		return script;
	}
	

private volatile JDialog progressDialog;

private volatile JProgressBar progressBar;

private void showProgress( final String msg )
{
	showProgress( msg, null, null );
}

private void showProgress( final String msg, final Long cur, final Long max )
{
	EventQueue.invokeLater( () ->
	{
		if ( progressDialog == null ) {
			final Window owner = IJ.getInstance();
			progressDialog = new JDialog( owner, "Fiji ♥ Appose" );
			progressDialog.setDefaultCloseOperation( WindowConstants.DO_NOTHING_ON_CLOSE );
			progressBar = new JProgressBar();
			progressDialog.getContentPane().add( progressBar );
			progressBar.setFont( new Font( "Courier", Font.PLAIN, 14 ) );
			progressBar.setString(
				"--------------------==================== " +
				"Building Python environment " +
				"====================--------------------"
			);
			progressBar.setStringPainted( true );
			progressBar.setIndeterminate( true );
			progressDialog.pack();
			progressDialog.setLocationRelativeTo( owner );
			progressDialog.setVisible( true );
		}
		if ( msg != null && !msg.trim().isEmpty() ) progressBar.setString( "Building Python environment: " + msg.trim() );
		if ( cur != null || max != null ) progressBar.setIndeterminate( false );
		if ( max != null ) progressBar.setMaximum( max.intValue() );
		if ( cur != null ) progressBar.setValue( cur.intValue() );
	} );
}
private void hideProgress()
{
	EventQueue.invokeLater( () ->
	{
		if ( progressDialog != null )
			progressDialog.dispose();
		progressDialog = null;
	} );
}
	
	/**
	 * Launch the interactive process
	 */
	
@Override
	public void run( final String arg )
	{
		// get/initialize the ROIManager
		rm = RoiManager.getInstance();
		if ( rm == null )
			rm = new RoiManager();
		rm.reset();
		rm.setVisible( true );
		
		// Get the current active image
		imp = WindowManager.getCurrentImage();
		if ( imp == null )
		{
			IJ.error( "No opened image found. Open one before" );
			return;
		}
		
		if ( (imp.getNSlices() <= 1) && (imp.getNFrames() <= 1) )
		{
			IJ.error("nnInteractive only works with 3D stacks");
			return;
		}
		
		if ( (imp.getNSlices() > 1) && (imp.getNFrames() > 1) )
		{
			IJ.error("nnInteractive cannot process 3D+time stacks. Process it frame by frame");
			return;
		}
		
		
		// Install/initialize the python env with nnInteractive
		initialize();
		
		// Prepare the image with the results as composite
		prepareResultImage();
		// add shortcuts on the image
		addShortcuts();
		// interface
		main_gui();
	}


}
