package org.mastodon.mamut.automahout.tool;

/** Builds an {@link EllipsoidFit3D.Fit} for tests outside this package. */
public final class EllipsoidFitTestAccess
{
	private EllipsoidFitTestAccess()
	{}

	public static EllipsoidFit3D.Fit fit( final double[] centre, final double[][] covariance )
	{
		return new EllipsoidFit3D.Fit( centre, covariance );
	}
}
