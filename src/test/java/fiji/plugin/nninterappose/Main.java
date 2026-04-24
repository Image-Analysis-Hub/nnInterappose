/*-
 * #%L
 * Use nnInteractive in Fiji
 * %%
 * Copyright (C) 2026 DSCB
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
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
