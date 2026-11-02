/*-
 * #%L
 * mastodon-automahout
 * %%
 * Copyright (C) 2025 Ko Sugawara
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.mastodon.mamut.automahout.tool;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;

/**
 * The image's physical extent, and the one question anybody asks about it: does this spot reach the
 * edge of the field of view?
 *
 * <p>Four places computed {@code dimension(d) * voxelSize(d)} for themselves, and the two that then
 * compared a position against it tested all three axes unconditionally. On a single-plane movie
 * {@code maxZ} is one voxel thick and every spot sits at {@code z = 0}, so any margin at all makes
 * every detection "at the border" — the same failure as counting the missing z axis in an aspect
 * ratio (see {@link SpotShape}). That mattered: {@code computeProblematicFraction} reports the
 * border fraction to the agent, which is told to raise {@code border_margin} when it is high, and
 * on 2D data it would have been 100% for any margin the agent chose.</p>
 *
 * <p>So the z axis is consulted only when the dataset has one. On one plane, "the border" is the
 * four edges of the image, which is what it means to the person looking at it.</p>
 */
public final class ImageBounds
{
	private ImageBounds()
	{}

	/**
	 * Physical size of the image in world units, {@code [x, y, z]}, or {@code null} when the sequence
	 * description cannot be read. Always three entries; on a single-plane dataset {@code [2]} is one
	 * voxel thick, which is why {@link #reachesBorder} ignores it unless told the data is volumetric.
	 */
	public static double[] physicalExtent( final ToolContext ctx )
	{
		if ( ctx == null )
			return null;
		try
		{
			final AbstractSpimData< ? > spimData = ctx.getProjectModel().getSharedBdvData().getSpimData();
			final BasicViewSetup setup = ( BasicViewSetup ) spimData.getSequenceDescription()
					.getViewSetupsOrdered().get( 0 );
			final Dimensions dims = setup.getSize();
			final VoxelDimensions voxelDims = setup.getVoxelSize();
			return new double[] {
					dims.dimension( 0 ) * voxelDims.dimension( 0 ),
					dims.dimension( 1 ) * voxelDims.dimension( 1 ),
					dims.dimension( 2 ) * voxelDims.dimension( 2 )
			};
		}
		catch ( final Exception e )
		{
			return null;
		}
	}

	/**
	 * In-plane voxel size in world units -- the larger of x and y -- or {@code NaN} when unreadable.
	 *
	 * <p>The smallest extent a detection can have and still be a body rather than a noise peak is a
	 * property of the sampling, not of the specimen, so the thresholds that express it belong in
	 * voxels. In plane, because a single-plane dataset's z voxel describes nothing.</p>
	 */
	public static double inPlaneVoxelSize( final ToolContext ctx )
	{
		if ( ctx == null )
			return Double.NaN;
		try
		{
			final AbstractSpimData< ? > spimData = ctx.getProjectModel().getSharedBdvData().getSpimData();
			final BasicViewSetup setup = ( BasicViewSetup ) spimData.getSequenceDescription()
					.getViewSetupsOrdered().get( 0 );
			final VoxelDimensions v = setup.getVoxelSize();
			return Math.max( v.dimension( 0 ), v.dimension( 1 ) );
		}
		catch ( final Exception e )
		{
			return Double.NaN;
		}
	}

	/**
	 * Whether {@code pos} lies within {@code margin} world units of any image face.
	 *
	 * <p>The z face is only one of them when {@code volumetric}; see the class comment. A
	 * non-positive margin means the check is switched off, matching every call site that reads a
	 * {@code border_margin} of 0 as "no border filtering", and a null extent fails OPEN (not at the
	 * border), because every caller deletes or flags what this returns true for.</p>
	 *
	 * @param extent     from {@link #physicalExtent}; {@code null} answers {@code false}
	 * @param pos        world position, three entries
	 * @param margin     world units from an image face; {@code <= 0} switches the check off
	 * @param volumetric whether the dataset has a real z axis ({@code ToolContext.isVolumetric()})
	 */
	public static boolean reachesBorder( final double[] extent, final double[] pos,
			final double margin, final boolean volumetric )
	{
		if ( extent == null || pos == null || !( margin > 0.0 ) )
			return false;
		final int axes = volumetric ? 3 : 2;
		for ( int d = 0; d < axes; d++ )
			if ( pos[ d ] < margin || pos[ d ] > extent[ d ] - margin )
				return true;
		return false;
	}
}
