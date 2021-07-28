package yeastmate;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.Iterator;
import java.util.stream.StreamSupport;

import javax.imageio.ImageIO;

import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.Previewable;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import net.imagej.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.array.ArrayLocalizingCursor;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

@Plugin(type = Command.class, headless = true,
	menuPath = "Plugins>YeastMate")
public class YeastMate implements Command, Previewable {
	private String tmpIpAdress = "*:5000";

	@Parameter
	private LogService log;

	@Parameter
	private StatusService statusService;

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

@	Parameter(label = "Minimum Intensity Quantile for Normalization", style = "slider", min = "0.001", max = "1", stepSize = "0.001")
	private Double minNormalizationQualtile = 0.01;

	@Parameter(label = "Maximum Intensity Quantile for Normalization", style = "slider", min = "0", max = "1", stepSize = "0.001")
	private Double maxNormalizationQualtile = 0.995;

	@Parameter
	private ImagePlus image;

	@Override
	public void run() {
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

		// set display range to quantiles as we send the AWT Image to detector
		image.setDisplayRange( minPerc, maxPerc );
		image.updateAndDraw();

		// TODO: reset display range afterwards?

		BufferedImage buff = image.getBufferedImage();
		String type = "image/png";

		try{
			URL url = new URL("http://" + ipAdress + "/predict_fiji");
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();

			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty( "Content-Type", type );

			ImageIO.write( buff, "png", conn.getOutputStream() );
			InputStream is = conn.getInputStream();

			JSONTokener tokener = new JSONTokener(new InputStreamReader(is));
			JSONObject result = new JSONObject(tokener);

			is.close();
			conn.disconnect();

			RoiManager manager = RoiManager.getInstance();
			if (manager == null){
				manager = new RoiManager();
			}

			ArrayImg< ShortType, ShortArray > masks = ArrayImgs.shorts( img.dimensionsAsLongArray() );

			for (int i = 0; i < result.getJSONArray("things").length(); i++) {
				JSONObject thing = result.getJSONArray("things").getJSONObject(i);

				double score = thing.getDouble("score");

				if (score > scoreThreshold) {
					JSONArray box = thing.getJSONArray("box");

					int x = box.getInt(0);
					int y = box.getInt(1);
					int w = box.getInt(2) - box.getInt(0);
					int h = box.getInt(3) - box.getInt(1);

					if (addBoxes == true) {
						Roi boxroi = new Roi(x,y,w,h);
						boxroi.setPosition(image);
						manager.addRoi(boxroi);
					}

					if (addMasks == true) {
						String maskString = thing.getString("mask");
						BufferedImage mask = base64toBufferedImg(maskString);
						ImagePlus maskImg = new ImagePlus("Temporary mask", mask);

						ImageProcessor ip = maskImg.getProcessor();
						ip.setThreshold(25, 255, ImageProcessor.NO_LUT_UPDATE);

						ThresholdToSelection tts = new ThresholdToSelection();
						Roi maskroi = tts.run(maskImg);

						maskroi.setPosition(image);
						maskroi.setLocation(x,y);

						manager.addRoi(maskroi);

						RandomAccess< ShortType > cur = masks.randomAccess();
						Cursor< UnsignedByteType > itMaskI = ImageJFunctions.wrapByte( maskImg ).localizingCursor();
						while (itMaskI.hasNext())
						{
							itMaskI.fwd();
							cur.setPosition( itMaskI );
							cur.get().setReal( itMaskI.get().get() / 255 * i );
						}
						maskImg.close();
					}
				}
			}
			if (showSegmentation)
				ImageJFunctions.show( masks, "segmentation of " + image.getTitle() );

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