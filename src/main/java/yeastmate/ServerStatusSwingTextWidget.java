package yeastmate;

import java.io.IOException;

import javax.swing.JLabel;

import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.scijava.Priority;
import org.scijava.plugin.Plugin;
import org.scijava.ui.swing.widget.SwingTextWidget;
import org.scijava.widget.InputWidget;
import org.scijava.widget.WidgetModel;

@Plugin(type=InputWidget.class, priority = Priority.HIGH)
public class ServerStatusSwingTextWidget extends SwingTextWidget
{

	public static final String CUSTOM_STYLE = "server-status";
	private JLabel statusLabel = new JLabel("");

	// timeout for status request
	private static final int TIMEOUT_MS = 500;

	@Override
	public void updateModel()
	{
		super.updateModel();
		queryServerStatus();
	}

	@Override
	public void set(WidgetModel model)
	{
		super.set( model );
		getComponent().add( statusLabel );
		refreshWidget();
	}

	private void queryServerStatus() {

		// build request with timeout
		RequestConfig config = RequestConfig.custom()
				.setConnectTimeout( TIMEOUT_MS )
				.setSocketTimeout( TIMEOUT_MS )
				.setConnectionRequestTimeout( TIMEOUT_MS ).build();
		CloseableHttpClient client = HttpClientBuilder.create().setDefaultRequestConfig( config ).build();

		// 1) GET /status endpoint at specified IP
		try ( CloseableHttpResponse response = client.execute(new HttpGet( "http://" + getValue() + "/status" )) ){

			// 2) check that the response code is 200: OK
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
			{
				statusLabel.setText( "Server status: ERROR" );
				return;
			}

			// 3) check that the response is JSON and contains name: YeastMate
			String responseBody = EntityUtils.toString( response.getEntity() );
			JSONObject result = null;
			String name = null;
			try {
				result = new JSONObject(responseBody);
				name = result.getString( "name" );
			}
			catch (JSONException e)
			{
				e.printStackTrace();
				statusLabel.setText("Server status: ERROR");
				return;
			}

			if (name==null | !name.equals( "YeastMate" ))
			{
				statusLabel.setText("Server status: ERROR");
				return;
			}
		}

		// if no connection can be made, set server status accordingly
		catch (IOException | IllegalArgumentException e)
		{
			statusLabel.setText( "Server status: ERROR" );
			return;
		}


		statusLabel.setText( "Server status: OK" );
		return;	
	}

}
