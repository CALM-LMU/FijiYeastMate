package yeastmate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.stream.StreamSupport;

import org.apache.commons.math3.stat.descriptive.rank.Percentile;
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

import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.FileInfo;
import ij.io.Opener;
import ij.io.TiffEncoder;
import ij.plugin.frame.RoiManager;
import ij.process.LUT;
import net.imagej.ImageJ;
import net.imagej.lut.LUTService;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.display.ColorTable;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

@Plugin(type = Command.class, headless = true,
	menuPath = "Plugins>YeastMate")
public class YeastMate implements Command, Previewable {
	private static final String LABEL_LUT_NAME = "Fire.lut";

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
	private Double scoreThresholdMating = 0.5;

	@Parameter(label = "Detection score threshold (buddings)", style = "slider", min = "0", max = "1", stepSize = "0.01")
	private Double scoreThresholdBudding = 0.5;

	@Parameter(label = "Minimum Intensity Quantile for Normalization", style = "slider", min = "0.005", max = "1", stepSize = "0.005")
	private Double minNormalizationQualtile = 0.01;

	@Parameter(label = "Maximum Intensity Quantile for Normalization", style = "slider", min = "0.005", max = "1", stepSize = "0.005")
	private Double maxNormalizationQualtile = 0.995;

	@Parameter(label = "Add single cell bounding boxes to ROI Manager?")
	private Boolean addSingleBoxes = false;

	@Parameter(label = "Add mating bounding boxes to ROI Manager?")
	private Boolean addMatingBoxes = true;

	@Parameter(label = "Add budding bounding boxes to ROI Manager?")
	private Boolean addBuddingBoxes = false;

	@Parameter(label = "Show segmentation mask?")
	private Boolean showSegmentation = true;

	@Parameter(label = "Only include cells from selected classes in mask?")
	private Boolean onlySelectedClassesInMask = false;

	@Parameter(label = "IP adress of detection server", style = "server-status")
	private String ipAdress = "127.0.0.1:5000";

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

//	private BufferedImage base64toBufferedImg(String b64img) {
//		BufferedImage image = null;
//		byte[] imageByte;
//
//		try {
//			imageByte = Base64.getDecoder().decode(b64img);
//			ByteArrayInputStream bis = new ByteArrayInputStream(imageByte);
//			image = ImageIO.read(bis);
//		}
//		catch (IOException e) {
//			log.info(e);
//		}
//
//		return image;
//	}

	public <T extends RealType<T>> void detect() {

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
		final double minPerc = minNormalizationQualtile == 0.0 ? image.getProcessor().getMin() : percentileCalculator.evaluate( pixels, minNormalizationQualtile * 100 );
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

		try{

			ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
			ByteArrayOutputStream jsonBytes = new ByteArrayOutputStream();

			// write parameters as JSON bytes
			PrintWriter pw = new PrintWriter(jsonBytes);
			pw.write("{\"0\":"+scoreThresholdSingle+",\"1\":" +scoreThresholdMating+",\"2\":"+scoreThresholdBudding+"}");
			pw.close();

			// write normalized image as tiff to bytes
			new TiffEncoder( normalizedIP.getFileInfo() ).write( imageBytes );

			// build multipart request as BentoML AnnotatedImage input spec
			MultipartEntityBuilder multipartBuilder = MultipartEntityBuilder.create();
			multipartBuilder.setContentType(ContentType.MULTIPART_FORM_DATA);
			multipartBuilder.setBoundary("TEHBOUNDARY");
			multipartBuilder.addBinaryBody("image", imageBytes.toByteArray(), ContentType.IMAGE_TIFF, "image.tiff");
			multipartBuilder.addBinaryBody( "annotations", jsonBytes.toByteArray(), ContentType.APPLICATION_JSON, "annotations.json");

			HttpPost conn = new HttpPost("http://" + ipAdress + "/predict");
			conn.setEntity(multipartBuilder.build());

			// get response as JSON
			CloseableHttpResponse response = HttpClients.createDefault().execute(conn);
//			JSONTokener tokener = new JSONTokener(response.getEntity().getContent());
			JSONObject result = new JSONObject(EntityUtils.toString( response.getEntity() ));

			response.close();

			RoiManager manager = RoiManager.getInstance();
			if (manager == null && (addSingleBoxes || addMatingBoxes || addBuddingBoxes )){
				manager = new RoiManager();
			}

			// NB: does not work because ImageIO can't read tiff (pre java 9)
//			BufferedImage base64toBufferedImg = base64toBufferedImg( result.getString( "mask" ) );
//			ImagePlus maaaaa = new ImagePlus("Temporary mask", base64toBufferedImg);
//			maaaaa.show();
			
			ImagePlus mask = null;
			if (showSegmentation)
			{
				// mask is returned as base64-encoded 16-bit TIFF
				mask = new Opener().openTiff( 
						new ByteArrayInputStream( Base64.getDecoder().decode( result.getString( "mask" ) ) ),
						"segmentation of " + image.getTitle() 
						);
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

//					objectsOverThreshold.add( Integer.parseInt( key ) );

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
						objectClass = "mating_mother";
					else if (objectClassCode.equals( "1.2" ))
						objectClass = "mating_daughter";
					else if (objectClassCode.equals( "2.1" ))
						objectClass = "budding_mother";
					else if (objectClassCode.equals( "2.2" ))
						objectClass = "budding_daughter";

					if ((addSingleBoxes && objectClassCode.startsWith("0")) || (addMatingBoxes && objectClassCode.startsWith("1")) || (addBuddingBoxes && objectClassCode.startsWith("2"))) {
						Roi boxroi = new Roi(x,y,w,h);

						cellsOfSelectedClasses.add( Integer.parseInt( key ) );
						// TODO: add name of parent here?
						boxroi.setName( key + ": " + objectClass);
						boxroi.setPosition(image);
						manager.addRoi(boxroi);
					}
				}

			}
			
			if (showSegmentation)
			{
				// set objects under threshold to zero
				if (onlySelectedClassesInMask)
					ImageJFunctions.wrapReal( mask ).forEach( v -> {
						if (!cellsOfSelectedClasses.contains( (int)(v.getRealFloat())))
							v.setZero();
					});

				// TODO: extract
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
					mask.setLut( lut );
				}
				
				
				mask.show();

			}
			
			
			
//			for (int i = 0; i < result.getJSONArray("things").length(); i++) {
//				JSONObject thing = result.getJSONArray("things").getJSONObject(i);
//				
//				result.getJSONObject( "things" );
//
//				double score = thing.getDouble("score");
//
//				if (score > scoreThreshold) {
//					JSONArray box = thing.getJSONArray("box");
//
//					int x = box.getInt(0);
//					int y = box.getInt(1);
//					int w = box.getInt(2) - box.getInt(0);
//					int h = box.getInt(3) - box.getInt(1);
//
//					if (addBoxes == true) {
//						Roi boxroi = new Roi(x,y,w,h);
//						boxroi.setPosition(image);
//						manager.addRoi(boxroi);
//					}
//
//					if (addMasks == true) {
//						String maskString = thing.getString("mask");
//						BufferedImage maskI = base64toBufferedImg(maskString);
//						ImagePlus maskImg = new ImagePlus("Temporary mask", maskI);
//
//						ImageProcessor ip = maskImg.getProcessor();
//						ip.setThreshold(25, 255, ImageProcessor.NO_LUT_UPDATE);
//
//						ThresholdToSelection tts = new ThresholdToSelection();
//						Roi maskroi = tts.run(maskImg);
//
//						maskroi.setPosition(image);
//						maskroi.setLocation(x,y);
//
//						manager.addRoi(maskroi);
//
//						RandomAccess< ShortType > cur = masks.randomAccess();
//						Cursor< UnsignedByteType > itMaskI = ImageJFunctions.wrapByte( maskImg ).localizingCursor();
//						while (itMaskI.hasNext())
//						{
//							itMaskI.fwd();
//							cur.setPosition( itMaskI );
//							cur.get().setReal( itMaskI.get().get() / 255 * i );
//						}
//						maskImg.close();
//					}
//				}
//			}

		}
		catch(IOException a) {
			log.info(a);
		}
		catch(JSONException c) {
			log.info(c);
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



//	protected void localChanged() {
//		if (localChoice.equals("Local detection")) {
//			tmpIpAdress = ipAdress;
//			ipAdress = "127.0.0.1:5000";
//		}
//		else {
//			ipAdress = tmpIpAdress;
//		}
//	}

	public static void main(final String... args) throws Exception {
		final ImageJ ij = new ImageJ();
		ij.launch(args);
		ij.command().run(YeastMate.class, true);
	}

}