package fiji.plugin.nninterappose;

import ij.IJ;
import net.imagej.ImageJ;

public class Main
{

	public static void main( final String[] args )
	{
		final ImageJ ij = new ImageJ();
		ij.launch();
		IJ.openImage( "/home/gaelle/Proj/IAH/HackatonAppose/nnInterappose/data/041825_crop.tif" ).show();
		Interact interact = new Interact();
		interact.run("");
		//ij.command().run( Interact.class, true );
	}
}
