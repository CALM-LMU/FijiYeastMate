package yeastmate;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

import javax.imageio.ImageIO;

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
	
	@Parameter(label = "Detection score threshold to add objects", style = "slider", min = "0", max = "1", stepSize = "0.1")
	private Double scoreThreshold = 0.5;

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

	public void detect() {
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
						ip.setThreshold(25, 255, 3);

						ThresholdToSelection tts = new ThresholdToSelection();
						Roi maskroi = tts.run(maskImg);
						
						maskroi.setPosition(image);
						maskroi.setLocation(x,y);

						maskImg.close();
						manager.addRoi(maskroi);
					}
				}
			}
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