package org.mastodon.mamut.automahout.elephant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;

/**
 * ELEPHANT reads {@code crop_box} at fixed offsets — Y is index 1 and index 4 — in BOTH its 2D and
 * its 3D branch, so the wire format is six numbers whatever the data is. Shipping four of them for a
 * 2D dataset made every cropped {@code /seg/predict} fail with {@code list index out of range},
 * which took out gap filling and predecessor recovery for a whole auto.correct run on
 * Fluo-N2DL-HeLA_01. These pin the arity and the 2D values.
 */
public class CropBoxArityTest
{
	/** The Fluo-N2DL-HeLa geometry that produced the failure: 1100x700, one plane. */
	private static final Dimensions TWO_D = new FinalDimensions( 1100L, 700L, 1L );

	private static final Dimensions THREE_D = new FinalDimensions( 512L, 512L, 60L );

	private static final int[] PATCH = { 368, 368, 4 };

	private static double[] world( final double cx, final double cy, final double cz, final double half )
	{
		return new double[] { cx - half, cy - half, cz - half, cx + half, cy + half, cz + half };
	}

	@Test
	public void twoDimensionalDataStillSendsThreeAxes()
	{
		// The bug: a four-element crop_box. Both arrays are length 3 by construction, and the caller
		// writes all six numbers, so the regression is only possible if this contract changes.
		final long[] origin = new long[ 3 ];
		final long[] size = new long[ 3 ];
		ElephantIntegration.cropBoxVoxels( TWO_D, new FinalVoxelDimensions( "um", 1, 1, 1 ),
				PATCH, world( 550, 242, 0, 12 ), origin, size );
		assertEquals( 3, origin.length );
		assertEquals( 3, size.length );
	}

	@Test
	public void theSinglePlaneIsDescribedAsOriginZeroSizeOne()
	{
		// Not a filler value: a 2D dataset really does start at plane 0 and really is one plane deep,
		// which is exactly what the reference PredictSpotsAction sends.
		final long[] origin = new long[ 3 ];
		final long[] size = new long[ 3 ];
		ElephantIntegration.cropBoxVoxels( TWO_D, new FinalVoxelDimensions( "um", 1, 1, 1 ),
				PATCH, world( 550, 242, 0, 12 ), origin, size );
		assertEquals( 0L, origin[ 2 ] );
		assertEquals( 1L, size[ 2 ] );
	}

	@Test
	public void aZeroVoxelDepthDoesNotPoisonTheOrigin()
	{
		// 2D datasets are routinely imported with a Z voxel size of 0. Dividing by it used to be on the
		// path to the Z origin; now the axis has no slack, so it is never consulted.
		final long[] origin = new long[ 3 ];
		final long[] size = new long[ 3 ];
		ElephantIntegration.cropBoxVoxels( TWO_D, new FinalVoxelDimensions( "um", 1, 1, 0 ),
				PATCH, world( 550, 242, 0, 12 ), origin, size );
		assertEquals( 0L, origin[ 2 ] );
		assertEquals( 1L, size[ 2 ] );
	}

	@Test
	public void theCropIsCenteredOnTheRequestedRegionAndCappedToThePatch()
	{
		final long[] origin = new long[ 3 ];
		final long[] size = new long[ 3 ];
		ElephantIntegration.cropBoxVoxels( TWO_D, new FinalVoxelDimensions( "um", 1, 1, 1 ),
				PATCH, world( 550, 350, 0, 12 ), origin, size );
		assertEquals( 368L, size[ 0 ] );
		assertEquals( 368L, size[ 1 ] );
		assertEquals( 550L - 184L, origin[ 0 ] );
		assertEquals( 350L - 184L, origin[ 1 ] );
	}

	@Test
	public void aRegionAtTheEdgeIsSlidInsideTheImage()
	{
		// The run's own failing request was crop_box origin (368, 58) on a 1100x700 image: a crop near
		// the border is moved in rather than truncated, so the patch the network sees is always full.
		final long[] origin = new long[ 3 ];
		final long[] size = new long[ 3 ];
		ElephantIntegration.cropBoxVoxels( TWO_D, new FinalVoxelDimensions( "um", 1, 1, 1 ),
				PATCH, world( 5, 5, 0, 12 ), origin, size );
		assertEquals( 0L, origin[ 0 ] );
		assertEquals( 0L, origin[ 1 ] );
		ElephantIntegration.cropBoxVoxels( TWO_D, new FinalVoxelDimensions( "um", 1, 1, 1 ),
				PATCH, world( 1095, 695, 0, 12 ), origin, size );
		assertTrue( origin[ 0 ] + size[ 0 ] <= 1100L, "crop runs past the right edge: " + origin[ 0 ] );
		assertTrue( origin[ 1 ] + size[ 1 ] <= 700L, "crop runs past the bottom edge: " + origin[ 1 ] );
	}

	@Test
	public void volumetricDataIsUnaffected()
	{
		// The 2D work must not have moved the 3D answer, which was never in question.
		final long[] origin = new long[ 3 ];
		final long[] size = new long[ 3 ];
		ElephantIntegration.cropBoxVoxels( THREE_D, new FinalVoxelDimensions( "um", 1, 1, 2 ),
				PATCH, world( 256, 256, 60, 10 ), origin, size );
		assertEquals( 4L, size[ 2 ] );
		assertEquals( 30L - 2L, origin[ 2 ] ); // z centre 60 um / 2 um per voxel = plane 30
	}
}
