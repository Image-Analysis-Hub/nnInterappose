package fiji.plugin.nninterappose;

import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
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
import javax.swing.JDialog;
import javax.swing.JFrame;
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

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
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
	private ImagePlus labels = null; // Results image
	private int nlabels = 0; // current number of labels created
	
	private Service nnservice = null; // running python service
	
	/**
	 * Create the parameter interface
	 */
	public void main_gui()
	{
		JFrame frame = new JFrame("Appose Environments");
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
		btnStop.addActionListener( e -> 
		{
			addRoi( true );
		});
		JButton btnAddNeg = new JButton( "Add negative ROI (or press '2')" );
		btnStop.addActionListener( e -> 
		{
			addRoi( false );
		});
		
		// send roi to nn
		JButton btnSendRoi = new JButton("Process ROIs");
		btnSendRoi.addActionListener( e -> 
		{
			if ( (nnservice != null) ) 
			{
				sendRois();
			}
			//frame.dispose();
		});
		frame.setLayout( new GridLayout(3, 3, 10, 10) );
		
		frame.add( btnStop );
		frame.add( Box.createGlue());
		frame.add( btnAddPos );
		frame.add( btnAddNeg );
		frame.add( Box.createGlue());
		frame.add( btnSendRoi );
	
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}
	
	public void addShortcuts()
	{
		 ImageWindow w = imp.getWindow();
		 
		if (w!=null)
		{
			KeyListener kl =  new KeyListener()
			{
						
				@Override
				public void keyReleased( KeyEvent e )
				{
					switch (e.getKeyChar() )
					{
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
		}
	}
	
	public void addRoi( boolean positive )
	{
		Roi roi = imp.getRoi();
		if ( roi == null )
		{
			IJ.log( "No active ROI to add to Manager" );
			return;
		}
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
	
	public < T extends RealType< T > & NativeType< T > >  void sendRois()
	{
		// Check if has been initialised else do it now
		if ( labels == null )
			prepareResultImage();
		
		try 
		{
			final String script = getScript( this.getClass().getResource("run_session.py" ) );
			final Map< String, Object > inputs = new HashMap<>();
			final List<List<Integer>> bboxes = new ArrayList<>();
			final List<Boolean> positives = new ArrayList<>(); 
			
			// Get all possible ROIs
			while ( rm.getCount() > 0 )
			{
				Roi roi = rm.getRoi(0);
				Rectangle rect = roi.getBounds();
				bboxes.add( Arrays.asList( roi.getZPosition(), rect.y, rect.x, rect.y+rect.height, rect.x+rect.width) );
				positives.add( !roi.getName().equals("negative") );
				rm.delete(0);

			}
			
			inputs.put("bboxs", bboxes);
			inputs.put("positives", positives);
			
			Task task = nnservice.task(script, inputs);
			task.listen( e -> {
				if (e.message != null) 
				{ 
					
					IJ.log( e.message );
				}
			});
			task.waitFor();
			if ( task.status != TaskStatus.COMPLETE )
				throw new RuntimeException( "Python script failed with error: " + task.error );

			boolean as_contour = false;
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
			else
			{
				final NDArray maskArr = ( NDArray ) task.outputs.get( "binary_stack" );
				Img< T > output = new ShmImg<>( maskArr );
				nlabels ++;
				LoopBuilder.setImages(output)
			    .forEachPixel(p -> p.setReal(p.getRealDouble() * nlabels));
				output =  ImgView.wrap( Views.dropSingletonDimensions(output) );
				//System.out.println(output.numDimensions());
				Img<T> imgLabels = ImageJFunctions.wrap( labels );
				//System.out.println(imgLabels.numDimensions());
				// Add src into dst (modifies imp in-place!)
				LoopBuilder.setImages( output, imgLabels )
				    .forEachPixel((s, d) -> d.setReal(d.getRealDouble() + s.getRealDouble()));

				// Refresh display
				labels.updateAndDraw();
				//final ImagePlus new_label = ImageJFunctions.wrap( output, "labels" );
				//labels = ic.run("Add stack", labels, new_label);
			}
		}
		catch ( Exception e)
		{
			IJ.error( "" + e );
		}
	}
	
	public void prepareResultImage()
	{
		labels = IJ.createImage(
			    "Black",               // title
			    "32-bit black",         // type + fill
			    imp.getWidth(),        // width
			    imp.getHeight(),       // height
			    imp.getNChannels(),
			    imp.getNSlices(),      // number of slices
			    imp.getNFrames()
			);
		labels.getProcessor().resetMinAndMax();
		useGlasbeyDarkLUT( labels );
		transferCalibration( imp, labels );
		labels.show();
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
		
		// Install/initialize the python env with nnInteractive
		initialize();
		
		// add shortcuts on the image
		addShortcuts();
		// interface
		main_gui();
	}


}
