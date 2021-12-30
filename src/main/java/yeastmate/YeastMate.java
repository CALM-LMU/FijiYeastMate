package yeastmate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.apache.http.ParseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.Previewable;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import hoerlteam.labeltools.LabelToolsTest;
import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.Wand;
import ij.io.FileInfo;
import ij.io.Opener;
import ij.io.TiffEncoder;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.LUT;
import net.imagej.ImageJ;
import net.imagej.lut.LUTService;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.display.ColorTable;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

@Plugin(type = Command.class, headless = true,
	menuPath = "Plugins>YeastMate")
public class YeastMate implements Command, Previewable {
	private static final String LABEL_LUT_NAME = "Fire.lut";
	private static final String BOUNDARY_STRING = "__BOUNDARY__";

	@Parameter
	private LogService log;

	@Parameter
	private StatusService statusService;
	
	@Parameter
	private LUTService lutService;

	@Parameter
	private ImagePlus image;

	@Parameter(label = "Detection score threshold (single cells)", style = "slider", min = "0", max = "1", stepSize = "0.01")
	private Double scoreThresholdSingle = 0.9;

	@Parameter(label = "Detection score threshold (matings)", style = "slider", min = "0", max = "1", stepSize = "0.01")
	private Double scoreThresholdMating = 0.75;

	@Parameter(label = "Detection score threshold (buddings)", style = "slider", min = "0", max = "1", stepSize = "0.01")
	private Double scoreThresholdBudding = 0.75;

	@Parameter(label = "Minimum Intensity Quantile for Normalization", style = "slider", min = "0.005", max = "1", stepSize = "0.005")
	private Double minNormalizationQualtile = 0.015;

	@Parameter(label = "Maximum Intensity Quantile for Normalization", style = "slider", min = "0.005", max = "1", stepSize = "0.005")
	private Double maxNormalizationQualtile = 0.985;

	@Parameter(label = "Add single cell ROIs to ROI Manager?")
	private Boolean addSingleRois = false;

	@Parameter(label = "Add mating ROIs to ROI Manager?")
	private Boolean addMatingRois = true;

	@Parameter(label = "Add budding ROIs to ROI Manager?")
	private Boolean addBuddingRois = false;

	@Parameter(label = "Show segmentation mask?")
	private Boolean showSegmentation = true;

	@Parameter(label = "Only include cells from selected classes in mask?")
	private Boolean onlySelectedClassesInMask = false;

	// add ROIs as outlines or boxes?
	private static Boolean addOutlineRois = true;

	@Parameter(label = "IP adress of detection server", style = "server-status")
	private String ipAdress = "127.0.0.1:11005";


	@Override
	public void run() {

		// RGB would require different quantile calc -> we do not support it a.t.m.
		if ( image.getFileInfo().fileType == FileInfo.RGB )
		{
			log.log( 0, "RGB images not supported, please convert your image to grayscale." );
			return;
		}

		detect();
	}

	private static JSONObject runRemoteDetection(ImagePlus normalizedImage, String ipAdress, double scoreThresholdSingle, double scoreThresholdMating, double scoreThresholdBudding )
	{
		ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream jsonBytes = new ByteArrayOutputStream();

		// write parameters as JSON bytes
		PrintWriter pw = new PrintWriter(jsonBytes);
		pw.write("{\"0\":"+scoreThresholdSingle+",\"1\":" +scoreThresholdMating+",\"2\":"+scoreThresholdBudding+"}");
		pw.close();

		// write normalized image as tiff to bytes
		try {new TiffEncoder( normalizedImage.getFileInfo() ).write( imageBytes );}
		catch (IOException e) {
			e.printStackTrace();
			return null;
		}

		// build multipart request as BentoML AnnotatedImage input spec
		MultipartEntityBuilder multipartBuilder = MultipartEntityBuilder.create();
		multipartBuilder.setContentType(ContentType.MULTIPART_FORM_DATA);
		multipartBuilder.setBoundary(BOUNDARY_STRING);
		multipartBuilder.addBinaryBody("image", imageBytes.toByteArray(), ContentType.IMAGE_TIFF, "image.tiff");
		multipartBuilder.addBinaryBody( "annotations", jsonBytes.toByteArray(), ContentType.APPLICATION_JSON, "annotations.json");

		HttpPost conn = new HttpPost("http://" + ipAdress + "/predict");
		conn.setEntity(multipartBuilder.build());

		// get response as JSON
		CloseableHttpResponse response = null;
		try {
			response = HttpClients.createDefault().execute(conn);
		} catch (IOException e) {
			e.printStackTrace();
		}

		JSONObject result;
		try {
			result = new JSONObject(EntityUtils.toString( response.getEntity() ));
		} catch (ParseException | JSONException | IOException e) {
			e.printStackTrace();
			return null;
		}

		try {response.close();}
		catch (IOException e) {
			e.printStackTrace();
		}
		
		return result;
	}
	
	private static ImagePlus parseMaskFromResult(JSONObject result, String title)
	{
		// mask is returned as base64-encoded 16-bit TIFF
		ImagePlus mask;
		try {
			mask = new Opener().openTiff( 
					new ByteArrayInputStream( Base64.getDecoder().decode( result.getString( "mask" ) ) ),
					"segmentation of " + title 
					);
		} catch (JSONException e) {
			e.printStackTrace();
			return null;
		}
		return mask;
	}

	private static <T extends RealType<T>> ImagePlus getNormalizedImagePlus(ImagePlus image, double minNormalizationQuantile, double maxNormalizationQualtile) {
		// get only currently displayed image as imglib2 RAI
		RandomAccessibleInterval<T> img = ImageJFunctions.wrapReal( image );
		if (image.getNChannels() > 1)
			img = Views.hyperSlice( img, 2, image.getChannel() - 1 );
		if (image.getNSlices() > 1)
			img = Views.hyperSlice( img, 2, image.getSlice() - 1 );
		if (image.getNFrames() > 1)
			img = Views.hyperSlice( img, 2, image.getFrame() - 1 );

		// get pixels as double array
		final double[] pixels = StreamSupport.stream( Views.iterable( img ).spliterator(), false ).mapToDouble( v -> v.getRealDouble() ).toArray();

		// get quantiles
		final Percentile percentileCalculator = new Percentile();
		final double minPerc = minNormalizationQuantile == 0.0 ? image.getProcessor().getMin() : percentileCalculator.evaluate( pixels, minNormalizationQuantile * 100 );
		final double maxPerc = percentileCalculator.evaluate( pixels, maxNormalizationQualtile * 100 );

		// make quantile-normalized copy of img
		RandomAccessibleInterval< FloatType > normalizedImg = ArrayImgs.floats( img.dimensionsAsLongArray() );
		RandomAccess< FloatType > raNormalized = normalizedImg.randomAccess();
		Cursor< T > cursorSource = Views.iterable( img ).cursor();
		while(cursorSource.hasNext())
		{
			cursorSource.fwd();
			raNormalized.setPosition( cursorSource );
			float x = (float) ( (cursorSource.get().getRealFloat() - minPerc) / (maxPerc-minPerc) );
			// TODO: clip to 0-1?
			raNormalized.get().set( x );
		}
		ImagePlus normalizedIP = ImageJFunctions.wrap( normalizedImg, "normalized " + image.getTitle());
		return normalizedIP;
	}

	public <T extends RealType<T>> void detect() {
	
		statusService.showStatus( 0, 3, "Preparing Request to Backend" );

		
		ImagePlus normalizedIP = getNormalizedImagePlus(image, minNormalizationQualtile, maxNormalizationQualtile);
	
		try{
			statusService.showStatus( 1, 3, "Getting Results from Backend" );
			
			JSONObject result = runRemoteDetection(normalizedIP, ipAdress, scoreThresholdSingle, scoreThresholdMating, scoreThresholdBudding);
	
	
			RoiManager manager = RoiManager.getInstance();
			if (manager == null && (addSingleRois || addMatingRois || addBuddingRois )){
				manager = new RoiManager();
			}
	
			statusService.showStatus( 2, 3, "Parsing Results" );
	
			SingleFrameDetectionResults detectionResults = new SingleFrameDetectionResults(result, null);

			final ImageProcessor maskProcessor = detectionResults.mask.getProcessor();
			final Wand wand = new Wand( maskProcessor );
			Map<Integer, Roi> labelsToRois = new HashMap<>();
	
			final Img< T > maskRAI = ImageJFunctions.wrapReal( detectionResults.mask );
			final Cursor< T > cur = maskRAI.localizingCursor();
			while (cur.hasNext())
			{
				cur.fwd();
				final Integer lab = (int) cur.get().getRealFloat();
				if (! labelsToRois.containsKey( lab ) && lab > 0)
				{
					wand.autoOutline( cur.getIntPosition( 0 ), cur.getIntPosition( 1 ), 0.0, Wand.EIGHT_CONNECTED );
					final Roi roi = new PolygonRoi( wand.xpoints, wand.ypoints, wand.npoints, Roi.FREELINE );
					labelsToRois.put( lab, roi );
				}
			}
	
			final JSONObject thingsJSON = result.getJSONObject( "detections" );
	
			
			final HashSet< Integer > cellsOfSelectedClasses = new HashSet<>();
			Iterator<?> keysIt = thingsJSON.keys();
			while (keysIt.hasNext())
			{
				String key = (String) keysIt.next();
				JSONObject thing = thingsJSON.getJSONObject( key );
				JSONArray classes = thing.getJSONArray("class");
				for (int i=0; i<classes.length(); i++)
				{
	
					JSONArray box = thing.getJSONArray("box");
	
					int x = box.getInt(0);
					int y = box.getInt(1);
					int w = box.getInt(2) - box.getInt(0);
					int h = box.getInt(3) - box.getInt(1);
	
					String objectClassCode = classes.getString(i);
					String objectClass = "";
	
					// TODO: extract
					if (objectClassCode.equals( "0" ))
						objectClass = "single_cell";
					else if (objectClassCode.equals( "1" ))
						objectClass = "mating";
					else if (objectClassCode.equals( "2" ))
						objectClass = "budding";
					else if (objectClassCode.equals( "1.1" ))
						objectClass = "mother";
					else if (objectClassCode.equals( "1.2" ))
						objectClass = "daughter";
					else if (objectClassCode.equals( "2.1" ))
						objectClass = "mother";
					else if (objectClassCode.equals( "2.2" ))
						objectClass = "daughter";
	
					if ((addSingleRois && objectClassCode.startsWith("0")) || (addMatingRois && objectClassCode.startsWith("1")) || (addBuddingRois && objectClassCode.startsWith("2"))) {
	
						Roi roi = null;
						// for compound objects or if we do not want outlines: get bbox ROI
						if ((objectClassCode.length() == 1) && !objectClassCode.equals( "0" ) || !addOutlineRois)
							roi = new Roi(x,y,w,h);
						else
							roi = labelsToRois.get( Integer.parseInt( key ) );
	
						// NB: total length of ROI name should be < 30 chars!
						// otherwise it will be truncated in label in resultsTable
						// see ij.plugin.filter.Analyzer
						// in our current naming scheme we have 9 chars for cell & parent id
						String roiName = detectionResults.allLabelRemap.get( Integer.parseInt( key )) + ": " + objectClass;
						// we have subobject of lifecycle transition -> add parent id in ROI name
						if (objectClassCode.length() > 1)
						{
							roiName += ", " + (objectClassCode.charAt( 0 ) == '1' ? "mating " : "budding ") + detectionResults.allLabelRemap.get(thing.getJSONArray("links").getInt( i-1 ));
						}
	
						cellsOfSelectedClasses.add( Integer.parseInt( key ) );
						roi.setName( roiName );
						roi.setPosition(image);
						manager.addRoi(roi);
					}
				}
	
			}

			if (showSegmentation)
			{
				// set objects under threshold to zero
				if (onlySelectedClassesInMask)
					ImageJFunctions.wrapReal(detectionResults.mask ).forEach( v -> {
						if (!cellsOfSelectedClasses.contains( (int)(v.getRealFloat())))
							v.setZero();
					});
				
				// relabel mask
				LabelToolsTest.relabelMap(ImageJFunctions.wrapShort(detectionResults.mask), detectionResults.allLabelRemap);
	
				// TODO: extract?
				if (lutService.findLUTs().containsKey( LABEL_LUT_NAME ))
				{
					ColorTable lutColorTable = lutService.loadLUT( lutService.findLUTs().get( LABEL_LUT_NAME ) );
	
					byte[] reds = new byte[256];
					byte[] greens = new byte[256];
					byte[] blues = new byte[256];
					for (int i = 0; i< 256; i++)
					{
						reds[i] = (byte) lutColorTable.getResampled( 0, 256, i );
						greens[i] = (byte) lutColorTable.getResampled( 1, 256, i );
						blues[i] = (byte) lutColorTable.getResampled( 2, 256, i );
					}
	
					LUT lut = new LUT(reds, greens, blues);
					detectionResults.mask.setLut( lut );
				}
	
				detectionResults.mask.show();
			}
	
		}
		catch(IOException a) {
			log.info(a);
		}
		catch(JSONException c) {
			log.info(c);
		}
	
		statusService.showStatus( 3, 3, "YeastMate: Done" );
	}
	
	private class SingleFrameDetectionResults
	{
		public LinkedHashSet<Integer> singleLabels;
		public LinkedHashSet<Integer> matingLabels;
		public LinkedHashSet<Integer> buddingLabels;
		public Map<Integer, Integer> singleLabelRemap;
		public Map<Integer, Integer> compoundLabelRemap;
		public Map<Integer, Integer> allLabelRemap;
		public ImagePlus mask;
		public JSONObject detections;
		public Map<Integer, double[]> compoundBoxes;
		public int maxLabel;

		public SingleFrameDetectionResults(JSONObject results, SingleFrameDetectionResults last) {
			
			// FIXME: proper name for mask
			mask = parseMaskFromResult(results, "mask");
			
			// FIXME: get max label from old frame
			int startValue = last == null ? 0 : last.maxLabel;
			
			singleLabels = LabelToolsTest.getLabelSet(ImageJFunctions.wrapShort(mask));
			singleLabelRemap = new HashMap<>();
			AtomicInteger idx = new AtomicInteger(startValue);
			for (Integer s: singleLabels) singleLabelRemap.put(s, idx.incrementAndGet());

			compoundBoxes = new HashMap<>();
			compoundLabelRemap = new HashMap<>();
			matingLabels = new LinkedHashSet<>();
			buddingLabels = new LinkedHashSet<>();

			try
			{
				detections = results.getJSONObject( "detections" );

				Iterator<?> keysIt = detections.keys();
				while (keysIt.hasNext())
				{
					String key = (String) keysIt.next();
					JSONObject detection = detections.getJSONObject( key );
					JSONArray classes = detection.getJSONArray("class");

					// get bounding box of object
					JSONArray box = detection.getJSONArray("box");
					int x = box.getInt(0);
					int y = box.getInt(1);
					int w = box.getInt(2) - box.getInt(0);
					int h = box.getInt(3) - box.getInt(1);

					// NB: compound objects have only one class, so we only look at index 0
					String objectClassCode = classes.getString(0);

					// save label if we have compound object
					if (objectClassCode.equals( "1" ))
						matingLabels.add(Integer.parseInt(key));
					if (objectClassCode.equals( "2" ))
						buddingLabels.add(Integer.parseInt(key));

					// save box if we have compound object
					if (objectClassCode.equals( "1" ) || objectClassCode.equals( "2" ))
						compoundBoxes.put(Integer.parseInt(key), new double[] {x, y, x+w, y+h});

				}
			}
			catch (JSONException e)
			{
				e.printStackTrace();
			}

			// map sequential for compound labels as well
			for (Integer s: matingLabels) compoundLabelRemap.put(s, idx.incrementAndGet());
			for (Integer s: buddingLabels) compoundLabelRemap.put(s, idx.incrementAndGet());

			// TODO: match labels from last

			allLabelRemap = new HashMap<>();
			allLabelRemap.putAll(singleLabelRemap);
			allLabelRemap.putAll(compoundLabelRemap);

			// get maximum (remapped) label
			AtomicInteger tmpMaxLabel = new AtomicInteger(-1);
			allLabelRemap.values().forEach(s -> {tmpMaxLabel.set( Math.max(s, tmpMaxLabel.get()));});
			maxLabel = Math.max(last == null ? 0 : last.maxLabel, tmpMaxLabel.get());
		}
	}

	@Override
	public void preview() {
		statusService.showStatus("Detecting yeast cells!");
	}

	@Override
	public void cancel() {
		log.info("YeastMate: canceled");
	}

	public static void main(final String... args) throws Exception {
		final ImageJ ij = new ImageJ();
		ij.launch(args);
		ij.command().run(YeastMate.class, true);
	}

}