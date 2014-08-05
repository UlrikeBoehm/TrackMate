package fiji.plugin.trackmate.tracking.sparselap;

import static fiji.plugin.trackmate.tracking.LAPUtils.checkFeatureMap;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_ALLOW_GAP_CLOSING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_ALLOW_TRACK_MERGING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_ALLOW_TRACK_SPLITTING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_ALTERNATIVE_LINKING_COST_FACTOR;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_CUTOFF_PERCENTILE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_GAP_CLOSING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_GAP_CLOSING_MAX_DISTANCE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_GAP_CLOSING_MAX_FRAME_GAP;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_MERGING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_MERGING_MAX_DISTANCE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_SPLITTING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.DEFAULT_SPLITTING_MAX_DISTANCE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALLOW_GAP_CLOSING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALLOW_TRACK_MERGING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALLOW_TRACK_SPLITTING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALTERNATIVE_LINKING_COST_FACTOR;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_CUTOFF_PERCENTILE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_LINKING_MAX_DISTANCE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_MERGING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_MERGING_MAX_DISTANCE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_SPLITTING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_SPLITTING_MAX_DISTANCE;
import static fiji.plugin.trackmate.util.TMUtils.checkMapKeys;
import static fiji.plugin.trackmate.util.TMUtils.checkParameter;
import ij.ImageJ;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.imglib2.algorithm.Benchmark;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.io.TmXmlReader;
import fiji.plugin.trackmate.providers.TrackerProvider;
import fiji.plugin.trackmate.tracking.SpotTracker;
import fiji.plugin.trackmate.tracking.jonkervolgenant.SparseCostMatrix;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import fiji.plugin.trackmate.visualization.trackscheme.TrackScheme;

public class SparseLAPSegmentTracker implements SpotTracker, Benchmark
{

	private static final String BASE_ERROR_MESSAGE = "[SparseLAPSegmentTracker] ";

	private final SimpleWeightedGraph< Spot, DefaultWeightedEdge > graph;

	private final Map< String, Object > settings;

	private String errorMessage;

	private Logger logger;

	private long processingTime;

	public SparseLAPSegmentTracker( final SimpleWeightedGraph< Spot, DefaultWeightedEdge > graph, final Map< String, Object > settings )
	{
		this.graph = graph;
		this.settings = settings;
	}

	@Override
	public SimpleWeightedGraph< Spot, DefaultWeightedEdge > getResult()
	{
		return graph;
	}

	@Override
	public boolean checkInput()
	{
		return true;
	}

	@Override
	public boolean process()
	{
		/*
		 * Check input now.
		 */

		// Check that the objects list itself isn't null
		if ( null == graph )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The input graph is null.";
			return false;
		}

		// Check parameters
		final StringBuilder errorHolder = new StringBuilder();
		if ( !checkSettingsValidity( settings, errorHolder ) )
		{
			errorMessage = errorHolder.toString();
			return false;
		}

		/*
		 * Process.
		 */

		final long start = System.currentTimeMillis();

		final boolean doGapClosing = ( Boolean ) settings.get( KEY_ALLOW_GAP_CLOSING );
		final boolean doTrackSplitting = ( Boolean ) settings.get( KEY_ALLOW_TRACK_SPLITTING );
		final boolean doTrackMerging = ( Boolean ) settings.get( KEY_ALLOW_TRACK_MERGING );

		if ( !doGapClosing && doTrackSplitting && doTrackMerging )
		{
			// Do nothing.
			final long end = System.currentTimeMillis();
			processingTime = end - start;
			return true;
		}

		final GraphSegmentSplitter segmentSplitter = new GraphSegmentSplitter( graph );
		final List< Spot > segmentEnds = segmentSplitter.getSegmentEnds();
		final List< Spot > segmentStarts = segmentSplitter.getSegmentStarts();
		final List< List< Spot >> segmentMiddles = segmentSplitter.getSegmentMiddles();

		if ( !doTrackMerging && !doTrackSplitting )
		{
			// Gap-closing only

			final Map< String, Object > sgc = new HashMap< String, Object >();
			sgc.put( KEY_GAP_CLOSING_MAX_FRAME_GAP, settings.get( KEY_GAP_CLOSING_MAX_FRAME_GAP ) );
			sgc.put( KEY_GAP_CLOSING_MAX_DISTANCE, settings.get( KEY_GAP_CLOSING_MAX_DISTANCE ) );
			sgc.put( KEY_GAP_CLOSING_FEATURE_PENALTIES, settings.get( KEY_GAP_CLOSING_FEATURE_PENALTIES ) );
			sgc.put( KEY_ALTERNATIVE_LINKING_COST_FACTOR, settings.get( KEY_ALTERNATIVE_LINKING_COST_FACTOR ) );
			sgc.put( KEY_CUTOFF_PERCENTILE, settings.get( KEY_CUTOFF_PERCENTILE ) );

			final GapClosingCostMatrixCreator costMatrixCreator = new GapClosingCostMatrixCreator( segmentEnds, segmentStarts, sgc );
			if ( !costMatrixCreator.checkInput() || !costMatrixCreator.process() )
			{
				errorMessage = costMatrixCreator.getErrorMessage();
				return false;
			}
			final SparseCostMatrix scm = costMatrixCreator.getResult();
			System.out.println( scm );// DEBUG

		}

		final long end = System.currentTimeMillis();
		processingTime = end - start;
		return true;
	}


	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}

	@Override
	public long getProcessingTime()
	{
		return processingTime;
	}

	@Override
	public void setLogger( final Logger logger )
	{
		this.logger = logger;
	}

	private static final boolean checkSettingsValidity( final Map< String, Object > settings, final StringBuilder str )
	{
		if ( null == settings )
		{
			str.append( "Settings map is null.\n" );
			return false;
		}

		boolean ok = true;
		// Gap-closing
		ok = ok & checkParameter( settings, KEY_ALLOW_GAP_CLOSING, Boolean.class, str );
		ok = ok & checkParameter( settings, KEY_GAP_CLOSING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkParameter( settings, KEY_GAP_CLOSING_MAX_FRAME_GAP, Integer.class, str );
		ok = ok & checkFeatureMap( settings, KEY_GAP_CLOSING_FEATURE_PENALTIES, str );
		// Splitting
		ok = ok & checkParameter( settings, KEY_ALLOW_TRACK_SPLITTING, Boolean.class, str );
		ok = ok & checkParameter( settings, KEY_SPLITTING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkFeatureMap( settings, KEY_SPLITTING_FEATURE_PENALTIES, str );
		// Merging
		ok = ok & checkParameter( settings, KEY_ALLOW_TRACK_MERGING, Boolean.class, str );
		ok = ok & checkParameter( settings, KEY_MERGING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkFeatureMap( settings, KEY_MERGING_FEATURE_PENALTIES, str );
		// Others
		ok = ok & checkParameter( settings, KEY_ALTERNATIVE_LINKING_COST_FACTOR, Double.class, str );
		ok = ok & checkParameter( settings, KEY_CUTOFF_PERCENTILE, Double.class, str );

		// Check keys
		final List< String > mandatoryKeys = new ArrayList< String >();
		mandatoryKeys.add( KEY_ALLOW_GAP_CLOSING );
		mandatoryKeys.add( KEY_GAP_CLOSING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_GAP_CLOSING_MAX_FRAME_GAP );
		mandatoryKeys.add( KEY_ALLOW_TRACK_SPLITTING );
		mandatoryKeys.add( KEY_SPLITTING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_ALLOW_TRACK_MERGING );
		mandatoryKeys.add( KEY_MERGING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_ALTERNATIVE_LINKING_COST_FACTOR );
		mandatoryKeys.add( KEY_CUTOFF_PERCENTILE );
		final List< String > optionalKeys = new ArrayList< String >();
		optionalKeys.add( KEY_GAP_CLOSING_FEATURE_PENALTIES );
		optionalKeys.add( KEY_SPLITTING_FEATURE_PENALTIES );
		optionalKeys.add( KEY_MERGING_FEATURE_PENALTIES );
		ok = ok & checkMapKeys( settings, mandatoryKeys, optionalKeys, str );

		return ok;
	}

	public static void main( final String[] args )
	{
		final File file = new File( "samples/FakeTracks.xml" );
		final TmXmlReader reader = new TmXmlReader( file );
		final Model model = reader.getModel();
		final SpotCollection spots = model.getSpots();
		System.out.println( spots );

		final Settings settings0 = new Settings();
		reader.readSettings( settings0, null, new TrackerProvider(), null, null, null );

		final Map< String, Object > settings1 = new HashMap< String, Object >();
		settings1.put( KEY_LINKING_MAX_DISTANCE, settings0.trackerSettings.get( KEY_LINKING_MAX_DISTANCE ) );
		settings1.put( KEY_ALTERNATIVE_LINKING_COST_FACTOR, settings0.trackerSettings.get( KEY_ALTERNATIVE_LINKING_COST_FACTOR ) );
		final SparseLAPFrameToFrameTracker ftfTracker = new SparseLAPFrameToFrameTracker( spots, settings1 );
		if ( !ftfTracker.checkInput() || !ftfTracker.process() )
		{
			System.err.println( ftfTracker.getErrorMessage() );
			return;
		}

		final SimpleWeightedGraph< Spot, DefaultWeightedEdge > graph = ftfTracker.getResult();
		final Map< String, Object > settings2 = new HashMap< String, Object >();

		// Gap closing
		settings2.put( KEY_ALLOW_GAP_CLOSING, DEFAULT_ALLOW_GAP_CLOSING );
		settings2.put( KEY_GAP_CLOSING_MAX_FRAME_GAP, DEFAULT_GAP_CLOSING_MAX_FRAME_GAP );
		settings2.put( KEY_GAP_CLOSING_MAX_DISTANCE, DEFAULT_GAP_CLOSING_MAX_DISTANCE );
		settings2.put( KEY_GAP_CLOSING_FEATURE_PENALTIES, new HashMap< String, Double >( DEFAULT_GAP_CLOSING_FEATURE_PENALTIES ) );
		// Track splitting
		settings2.put( KEY_ALLOW_TRACK_SPLITTING, DEFAULT_ALLOW_TRACK_SPLITTING );
		settings2.put( KEY_SPLITTING_MAX_DISTANCE, DEFAULT_SPLITTING_MAX_DISTANCE );
		settings2.put( KEY_SPLITTING_FEATURE_PENALTIES, new HashMap< String, Double >( DEFAULT_SPLITTING_FEATURE_PENALTIES ) );
		// Track merging
		settings2.put( KEY_ALLOW_TRACK_MERGING, DEFAULT_ALLOW_TRACK_MERGING );
		settings2.put( KEY_MERGING_MAX_DISTANCE, DEFAULT_MERGING_MAX_DISTANCE );
		settings2.put( KEY_MERGING_FEATURE_PENALTIES, new HashMap< String, Double >( DEFAULT_MERGING_FEATURE_PENALTIES ) );
		// Others
		settings2.put( KEY_ALTERNATIVE_LINKING_COST_FACTOR, DEFAULT_ALTERNATIVE_LINKING_COST_FACTOR );
		settings2.put( KEY_CUTOFF_PERCENTILE, DEFAULT_CUTOFF_PERCENTILE );

		final SparseLAPSegmentTracker stsTracker = new SparseLAPSegmentTracker( graph, settings2 );
		if ( !stsTracker.checkInput() || !stsTracker.process() )
		{
			System.err.println( stsTracker.getErrorMessage() );
			return;
		}

		model.setTracks( graph, true );

		ImageJ.main( args );
		final SelectionModel sm = new SelectionModel( model );
		final HyperStackDisplayer view = new HyperStackDisplayer( model, sm );
		view.render();
		final TrackScheme trackScheme = new TrackScheme( model, sm );
		trackScheme.render();

	}

}
