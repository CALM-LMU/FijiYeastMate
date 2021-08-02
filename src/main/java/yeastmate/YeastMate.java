package yeastmate;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.StreamSupport;

import javax.imageio.ImageIO;

import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.apache.http.HttpEntity;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpRequestExecutor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.json.JSONWriter;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.Previewable;
import org.scijava.io.IOService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.FileInfo;
import ij.io.Opener;
import ij.io.TiffEncoder;
import ij.plugin.BMP_Writer;
import ij.plugin.LutLoader;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.LUT;
import io.scif.formats.tiff.TiffSaver;
import io.scif.media.imageioimpl.plugins.tiff.TIFFImageWriter;
import io.scif.services.JAIIIOService;
import io.scif.services.JAIIIOServiceImpl;
import net.imagej.ImageJ;
import net.imagej.lut.LUTFinder;
import net.imagej.lut.LUTService;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.stats.Normalize;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.display.ColorTable;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.array.ArrayLocalizingCursor;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

@Plugin(type = Command.class, headless = true,
	menuPath = "Plugins>YeastMate")
public class YeastMate implements Command, Previewable {
	private static final String LABEL_LUT_NAME = "Fire.lut";

	private String tmpIpAdress = "*:5000";

	@Parameter
	private LogService log;

	@Parameter
	private StatusService statusService;
	
	@Parameter
	private IOService ioService;
	
	@Parameter
	private LUTService lutService;

	@Parameter(label = "Local or remote detection", choices = { "Local detection", "Remote detection" }, callback="localChanged")
	private String localChoice;

	@Parameter(label = "IP adress of remote detection server")
	private String ipAdress = "127.0.0.1:5000";

	@Parameter(label = "Add bounding boxes to ROI Manager?")
	private Boolean addBoxes = true; 

	@Parameter(label = "Add masks to ROI Manager?")
	private Boolean addMasks = true;

	@Parameter(label = "Show segmentation mask?")
	private Boolean showSegmentation = true;

	@Parameter(label = "Detection score threshold to add objects", style = "slider", min = "0", max = "1", stepSize = "0.1")
	private Double scoreThreshold = 0.5;

	@Parameter(label = "Minimum Intensity Quantile for Normalization", style = "slider", min = "0.001", max = "1", stepSize = "0.001")
	private Double minNormalizationQualtile = 0.01;

	@Parameter(label = "Maximum Intensity Quantile for Normalization", style = "slider", min = "0", max = "1", stepSize = "0.001")
	private Double maxNormalizationQualtile = 0.995;

	@Parameter
	private ImagePlus image;

	private static final String IMAGE_REQUEST_TYPE = "image/tiff";

	@Override
	public void run() {

		// RGB would require different quantile calc -> we do not support it atm.
		if ( image.getFileInfo().fileType == FileInfo.RGB )
		{
			log.log( 0, "RGB images not supported, please convert your image to grayscale." );
			return;
		}

		detect();
	}

	private BufferedImage base64toBufferedImg(String b64img) {
		BufferedImage image = null;
		byte[] imageByte;

		try {
			imageByte = Base64.getDecoder().decode(b64img);
			ByteArrayInputStream bis = new ByteArrayInputStream(imageByte);
			image = ImageIO.read(bis);
		}
		catch (IOException e) {
			log.info(e);
		}

		return image;
	}

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
			// TODO: set actual parameters here
			PrintWriter pw = new PrintWriter(jsonBytes);
			pw.write("{\"0\":0.9,\"1\":0.5,\"2\":0.5}");
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
			JSONTokener tokener = new JSONTokener(response.getEntity().getContent());
			JSONObject result = new JSONObject(tokener);

			response.close();

			RoiManager manager = RoiManager.getInstance();
			if (manager == null){
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

			final JSONObject thingsJSON = result.getJSONObject( "things" );

			final HashSet< Integer > objectsOverThreshold = new HashSet<>();
			for (final String key : thingsJSON.keySet())
			{
				JSONObject thing = thingsJSON.getJSONObject( key );
				double score = thing.getDouble("score");
				
				if (score > scoreThreshold) {

					objectsOverThreshold.add( Integer.parseInt( key ) );

					JSONArray box = thing.getJSONArray("box");

					int x = box.getInt(0);
					int y = box.getInt(1);
					int w = box.getInt(2) - box.getInt(0);
					int h = box.getInt(3) - box.getInt(1);

					String objectClassCode = (thing.getString( "class" ));
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

					if (addBoxes == true) {
						Roi boxroi = new Roi(x,y,w,h);
						boxroi.setName( key + ": " + objectClass);
						boxroi.setPosition(image);
						manager.addRoi(boxroi);
					}
				}
				
			}
			
			if (showSegmentation)
			{
				// set objects under threshold to zero
				ImageJFunctions.wrapReal( mask ).forEach( v -> {
					if (!objectsOverThreshold.contains( (int)(v.getRealFloat())))
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

	protected void localChanged() {
		if (localChoice.equals("Local detection")) {
			tmpIpAdress = ipAdress;
			ipAdress = "127.0.0.1:5000";
		}
		else {
			ipAdress = tmpIpAdress;
		}
	}

	public static void main(final String... args) throws Exception {
		final ImageJ ij = new ImageJ();
		ij.launch(args);
		ij.command().run(YeastMate.class, true);
	}

}