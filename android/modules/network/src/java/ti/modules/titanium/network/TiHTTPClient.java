/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2010-2014 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package ti.modules.titanium.network;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.ProtocolException;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthSchemeFactory;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.ContentBody;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultRedirectHandler;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollProxy;
import org.appcelerator.kroll.common.Log;
import org.appcelerator.titanium.TiApplication;
import org.appcelerator.titanium.TiBlob;
import org.appcelerator.titanium.TiC;
import org.appcelerator.titanium.TiFileProxy;
import org.appcelerator.titanium.io.TiBaseFile;
import org.appcelerator.titanium.io.TiFile;
import org.appcelerator.titanium.io.TiFileFactory;
import org.appcelerator.titanium.io.TiResourceFile;
import org.appcelerator.titanium.util.TiConvert;
import org.appcelerator.titanium.util.TiMimeTypeHelper;
import org.appcelerator.titanium.util.TiPlatformHelper;
import org.appcelerator.titanium.util.TiUrl;

import ti.modules.titanium.xml.DocumentProxy;
import ti.modules.titanium.xml.XMLModule;
import android.net.Uri;
import android.os.Build;
import android.util.Base64OutputStream;

public class TiHTTPClient
{
	private static final String TAG = "TiHttpClient";
	private static final int DEFAULT_MAX_BUFFER_SIZE = 512 * 1024;
	private static final String PROPERTY_MAX_BUFFER_SIZE = "ti.android.httpclient.maxbuffersize";
	private static final int PROTOCOL_DEFAULT_PORT = -1;
	private static final String TITANIUM_ID_HEADER = "X-Titanium-Id";
	private static final String TITANIUM_USER_AGENT = "Appcelerator Titanium/" + TiApplication.getInstance().getTiBuildVersion()
	                                                  + " ("+ Build.MODEL + "; Android API Level: "
	                                                  + Integer.toString(Build.VERSION.SDK_INT) + "; "
	                                                  + TiPlatformHelper.getInstance().getLocale() +";)";
	private static final String[] FALLBACK_CHARSETS = {HTTP.UTF_8, HTTP.ISO_8859_1};

	// Regular expressions for detecting charset information in response documents (ex: html, xml).
	private static final String HTML_META_TAG_REGEX = "charset=([^\"\']*)";
	private static final String XML_DECLARATION_TAG_REGEX = "encoding=[\"\']([^\"\']*)[\"\']";

	private static AtomicInteger httpClientThreadCounter;

	private DefaultHttpClient client;
	private KrollProxy proxy;
	private int readyState;
	private String responseText;
	private DocumentProxy responseXml;
	private int status;
	private String statusText;
	private boolean connected;
	private HttpRequest request;
	private HttpResponse response;
	private Header[] responseHeaders;
	private String method;
	private HttpHost host;
	private LocalResponseHandler handler;
	private Credentials credentials;
	private TiBlob responseData;
	private OutputStream responseOut;
	private StatusLine responseStatusLine;
	private String charset;
	private String contentType;
	private long maxBufferSize;
	private ArrayList<NameValuePair> nvPairs;
	private HashMap<String, ContentBody> parts;
	private Object data;
	private boolean needMultipart;
	private Thread clientThread;
	private boolean aborted;
	private int timeout = -1;
	private boolean autoEncodeUrl = true;
	private boolean autoRedirect = true;
	private Uri uri;
	private String url;
	private String redirectedLocation;
	private ArrayList<File> tmpFiles = new ArrayList<File>();
	private ArrayList<X509TrustManager> trustManagers = new ArrayList<X509TrustManager>();
	private ArrayList<X509KeyManager> keyManagers = new ArrayList<X509KeyManager>();
	protected SecurityManagerProtocol securityManager;
	private int tlsVersion = NetworkModule.TLS_DEFAULT;

	private static CookieStore cookieStore = NetworkModule.getHTTPCookieStoreInstance();


	protected HashMap<String,String> headers = new HashMap<String,String>();

	private Hashtable<String, AuthSchemeFactory> customAuthenticators = new Hashtable<String, AuthSchemeFactory>(1);

	public static final int READY_STATE_UNSENT = 0; // Unsent, open() has not yet been called
	public static final int READY_STATE_OPENED = 1; // Opened, send() has not yet been called
	public static final int READY_STATE_HEADERS_RECEIVED = 2; // Headers received, headers have returned and the status is available
	public static final int READY_STATE_LOADING = 3; // Loading, responseText is being loaded with data
	public static final int READY_STATE_DONE = 4; // Done, all operations have finished

	class RedirectHandler extends DefaultRedirectHandler
	{
		@Override
		public URI getLocationURI(HttpResponse response, HttpContext context)
				throws ProtocolException {

			if (response == null) {
				throw new IllegalArgumentException("HTTP response may not be null");
			}
			// get the location header to find out where to redirect to
			Header locationHeader = response.getFirstHeader("location");
			if (locationHeader == null) {
				// got a redirect response, but no location header
				throw new ProtocolException("Received redirect response "
					+ response.getStatusLine() + " but no location header");
			}

			// bug #2156: https://appcelerator.lighthouseapp.com/projects/32238/tickets/2156-android-invalid-redirect-alert-on-xhr-file-download
			// in some cases we have to manually replace spaces in the URI (probably because the HTTP server isn't correctly escaping them)
			String location = locationHeader.getValue().replaceAll (" ", "%20");
			response.setHeader("location", location);
			redirectedLocation = location;

			return super.getLocationURI(response, context);
		}

		@Override
		public boolean isRedirectRequested(HttpResponse response, HttpContext context)
		{
			if (autoRedirect) {
				return super.isRedirectRequested(response, context);
			} else {
				return false;
			}
		}
	}

	class LocalResponseHandler implements ResponseHandler<String>
	{
		public WeakReference<TiHTTPClient> client;
		public InputStream is;
		public HttpEntity entity;
		public TiFile responseFile;

		public LocalResponseHandler(TiHTTPClient client)
		{
			this.client = new WeakReference<TiHTTPClient>(client);
		}

		public String handleResponse(HttpResponse response) throws HttpResponseException, IOException
		{
			connected = true;
			String clientResponse = null;
			Header contentEncoding = null;

			if (client != null) {
				TiHTTPClient c = client.get();
				if (c != null) {
					c.response = response;
					c.setReadyState(READY_STATE_HEADERS_RECEIVED);
					c.setStatus(response.getStatusLine().getStatusCode());
					c.setStatusText(response.getStatusLine().getReasonPhrase());
					c.setReadyState(READY_STATE_LOADING);

					if (c.proxy.hasProperty(TiC.PROPERTY_FILE)) {
						Object f = c.proxy.getProperty(TiC.PROPERTY_FILE);
						if (f instanceof String) {
							String fileName = (String) f;
							TiBaseFile baseFile = TiFileFactory.createTitaniumFile(fileName, false);
							if (baseFile instanceof TiFile) {
								responseFile = (TiFile) baseFile;
							}
						}
						if (responseFile == null && Log.isDebugModeEnabled()) {
							Log.w(TAG, "Ignore the provided response file because it is not valid / writable.");
						}
					}
				}

				if (Log.isDebugModeEnabled()) {
					try {
						Log.d(TAG, "Entity Type: " + response.getEntity().getClass());
						Log.d(TAG, "Entity Content Type: " + response.getEntity().getContentType().getValue());
						Log.d(TAG, "Entity isChunked: " + response.getEntity().isChunked());
						Log.d(TAG, "Entity isStreaming: " + response.getEntity().isStreaming());
					} catch (Throwable t) {
						// Ignore
					}
				}

				responseStatusLine = response.getStatusLine();
				entity = response.getEntity();
				contentEncoding = response.getFirstHeader("Content-Encoding");
				if (entity != null) {
					if (entity.getContentType() != null) {
						contentType = entity.getContentType().getValue();
					}
					if (contentEncoding != null && contentEncoding.getValue().equalsIgnoreCase("gzip") && entity.getContentLength() != 0) {
						is = new GZIPInputStream(entity.getContent());
					} else {
						is = entity.getContent();
					}
					charset = EntityUtils.getContentCharSet(entity);
				} else {
					is = null;
				}

				responseData = null;

				if (is != null) {
					long contentLength = entity.getContentLength();
					Log.d(TAG, "Content length: " + contentLength, Log.DEBUG_MODE);
					int count = 0;
					long totalSize = 0;
					byte[] buf = new byte[4096];
					Log.d(TAG, "Available: " + is.available(), Log.DEBUG_MODE);

					if (entity != null) {
						charset = EntityUtils.getContentCharSet(entity);
					}
					while((count = is.read(buf)) != -1) {
						if (aborted) {
							break;
						}
						totalSize += count;
						try {
							handleEntityData(buf, count, totalSize, contentLength);
						} catch (IOException e) {
							Log.e(TAG, "Error handling entity data", e);

							// TODO
							//Context.throwAsScriptRuntimeEx(e);
						}
					}
					if (entity != null) {
						try {
							entity.consumeContent();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					if (totalSize > 0) {
						finishedReceivingEntityData(totalSize);
					}
				}
			}
			return clientResponse;
		}

		private TiFile createFileResponseData(boolean dumpResponseOut) throws IOException
		{
			TiFile tiFile = null;
			File outFile = null;
			if (responseFile != null) {
				tiFile = responseFile;
				outFile = tiFile.getFile();
				try {
					responseOut = new FileOutputStream(outFile, dumpResponseOut);
					// If the response file is in the temp folder, don't delete it during cleanup.
					TiApplication app = TiApplication.getInstance();
					if (app != null) {
						app.getTempFileHelper().excludeFileOnCleanup(outFile);
					}
				} catch (FileNotFoundException e) {
					responseFile = null;
					tiFile = null;
					if (Log.isDebugModeEnabled()) {
						Log.e(TAG, "Unable to create / write to the response file. Will write the response data to the internal data directory.");
					}
				}
			}

			if (tiFile == null) {
				outFile = TiFileFactory.createDataFile("tihttp", "tmp");
				tiFile = new TiFile(outFile, outFile.getAbsolutePath(), false);
			}

			if (dumpResponseOut) {
				ByteArrayOutputStream byteStream = (ByteArrayOutputStream) responseOut;
				tiFile.write(TiBlob.blobFromData(byteStream.toByteArray()), false);
			}

			responseOut = new FileOutputStream(outFile, dumpResponseOut);
			responseData = TiBlob.blobFromFile(tiFile, contentType);
			return tiFile;
		}

		private void handleEntityData(byte[] data, int size, long totalSize, long contentLength) throws IOException
		{
			if (responseOut == null) {
				if (responseFile != null) {
					createFileResponseData(false);
				}
				else if (contentLength > maxBufferSize) {
					createFileResponseData(false);
				} else {
					long streamSize = contentLength > 0 ? contentLength : 512;
					responseOut = new ByteArrayOutputStream((int)streamSize);
				}
			}
			if (totalSize > maxBufferSize && responseOut instanceof ByteArrayOutputStream) {
				// Content length may not have been reported, dump the current stream
				// to a file and re-open as a FileOutputStream w/ append
				createFileResponseData(true);
			}

			responseOut.write(data, 0, size);

			KrollDict callbackData = new KrollDict();
			callbackData.put("totalCount", contentLength);
			callbackData.put("totalSize", totalSize);
			callbackData.put("size", size);

			byte[] blobData = new byte[size];
			System.arraycopy(data, 0, blobData, 0, size);

			TiBlob blob = TiBlob.blobFromData(blobData, contentType);
			callbackData.put("blob", blob);
			double progress = ((double)totalSize)/((double)contentLength);
			// return progress as -1 if it is outside the valid range
			if (progress > 1 || progress < 0) {
				progress = NetworkModule.PROGRESS_UNKNOWN;
			}
			callbackData.put("progress", progress);

			dispatchCallback("ondatastream", callbackData);
		}

		private void finishedReceivingEntityData(long contentLength) throws IOException
		{
			if (responseOut instanceof ByteArrayOutputStream) {
				ByteArrayOutputStream byteStream = (ByteArrayOutputStream) responseOut;
				responseData = TiBlob.blobFromData(byteStream.toByteArray(), contentType);
			}
			responseOut.close();
			responseOut = null;
		}

		private void setResponseText(HttpEntity entity) throws IOException, ParseException
		{
			if (entity != null) {
				responseText = EntityUtils.toString(entity);
			}
		}
	}

	private interface ProgressListener
	{
		public void progress(int progress);
	}

	private class ProgressEntity implements HttpEntity
	{
		private HttpEntity delegate;
		private ProgressListener listener;
		public ProgressEntity(HttpEntity delegate, ProgressListener listener)
		{
			this.delegate = delegate;
			this.listener = listener;
		}

		public void consumeContent() throws IOException
		{
			delegate.consumeContent();
		}

		public InputStream getContent() throws IOException, IllegalStateException
		{
			return delegate.getContent();
		}

		public Header getContentEncoding()
		{
			return delegate.getContentEncoding();
		}

		public long getContentLength()
		{
			return delegate.getContentLength();
		}

		public Header getContentType()
		{
			return delegate.getContentType();
		}

		public boolean isChunked()
		{
			return delegate.isChunked();
		}

		public boolean isRepeatable()
		{
			return delegate.isRepeatable();
		}

		public boolean isStreaming()
		{
			return delegate.isStreaming();
		}

		public void writeTo(OutputStream stream) throws IOException
		{
			OutputStream progressOut = new ProgressOutputStream(stream, listener);
			delegate.writeTo(progressOut);
		}
	}

	private class ProgressOutputStream extends FilterOutputStream
	{
		private ProgressListener listener;
		private int transferred = 0, lastTransferred = 0;

		public ProgressOutputStream(OutputStream delegate, ProgressListener listener)
		{
			super(delegate);
			this.listener = listener;
		}

		private void fireProgress()
		{
			// filter to 512 bytes of granularity
			if (transferred - lastTransferred >= 512) {
				lastTransferred = transferred;
				listener.progress(transferred);
			}
		}

		@Override
		public void write(int b) throws IOException
		{
			//Donot write if request is aborted
			if (!aborted) {
				super.write(b);
				transferred++;
				fireProgress();
			}
		}
	}

	public TiHTTPClient(KrollProxy proxy)
	{
		this.proxy = proxy;
		this.client = createClient();

		if (httpClientThreadCounter == null) {
			httpClientThreadCounter = new AtomicInteger();
		}
		readyState = 0;
		responseText = "";
		credentials = null;
		connected = false;
		this.nvPairs = new ArrayList<NameValuePair>();
		this.parts = new HashMap<String,ContentBody>();
		this.maxBufferSize = TiApplication.getInstance()
				.getAppProperties().getInt(PROPERTY_MAX_BUFFER_SIZE, DEFAULT_MAX_BUFFER_SIZE);
	}

	public int getReadyState()
	{
		synchronized(this) {
			this.notify();
		}
		return readyState;
	}

	public boolean validatesSecureCertificate()
	{
		if (proxy.hasProperty("validatesSecureCertificate")) {
			return TiConvert.toBoolean(proxy.getProperty("validatesSecureCertificate"));

		} else {
			if (TiApplication.getInstance().getDeployType().equals(
					TiApplication.DEPLOY_TYPE_PRODUCTION)) {
				return true;
			}
		}
		return false;
	}

	public void addAuthFactory(String scheme, AuthSchemeFactory theFactory)
	{
		customAuthenticators.put(scheme, theFactory);
	}

	public void setReadyState(int readyState)
	{
		Log.d(TAG, "Setting ready state to " + readyState, Log.DEBUG_MODE);
		this.readyState = readyState;
		KrollDict data = new KrollDict();
		data.put("readyState", Integer.valueOf(readyState));
		dispatchCallback("onreadystatechange", data);

		if (readyState == READY_STATE_DONE) {
			KrollDict data1 = new KrollDict();
			data1.putCodeAndMessage(TiC.ERROR_CODE_NO_ERROR, null);
			dispatchCallback("onload", data1);
		}
	}

	private String decodeResponseData(String charsetName) {
		Charset charset;
		try {
			charset = Charset.forName(charsetName);

		} catch (IllegalArgumentException e) {
			Log.e(TAG, "Could not find charset: " + e.getMessage());
			return null;
		}

		CharsetDecoder decoder = charset.newDecoder();
		ByteBuffer in = ByteBuffer.wrap(responseData.getBytes());

		try {
			CharBuffer decodedText = decoder.decode(in);
			return decodedText.toString();

		} catch (CharacterCodingException e) {
			return null;

		} catch (OutOfMemoryError e) {
			Log.e(TAG, "Not enough memory to decode response data.");
			return null;
		}
	}

	/**
	 * Attempts to scan the response data to determine the encoding of the text.
	 * Looks for meta information usually found in HTML or XML documents.
	 *
	 * @return The name of the encoding if detected, otherwise null if no encoding could be determined.
	 */
	private String detectResponseDataEncoding() {
		String regex;
		if (contentType == null) {
			Log.w(TAG, "Could not detect charset, no content type specified.", Log.DEBUG_MODE);
			return null;

		} else if (contentType.contains("xml")) {
			regex = XML_DECLARATION_TAG_REGEX;

		} else if (contentType.contains("html")) {
			regex = HTML_META_TAG_REGEX;

		} else {
			Log.w(TAG, "Cannot detect charset, unknown content type: " + contentType, Log.DEBUG_MODE);
			return null;
		}

		CharSequence responseSequence = responseData.toString();
		Pattern pattern = Pattern.compile(regex);
		Matcher matcher = pattern.matcher(responseSequence);
		if (matcher.find()) {
			return matcher.group(1);
		}

		return null;
	}

	public String getResponseText()
	{
		if (responseText != null || responseData == null) {
			return responseText;
		}

		// First try decoding the response data using the charset
		// specified in the response content-type header.
		if (charset != null) {
			responseText = decodeResponseData(charset);
			if (responseText != null) {
				return responseText;
			}
		}

		// If the first attempt to decode fails try detecting the correct
		// charset by scanning the response data.
		String detectedCharset = detectResponseDataEncoding();
		if (detectedCharset != null) {
			Log.d(TAG, "detected charset: " + detectedCharset, Log.DEBUG_MODE);
			responseText = decodeResponseData(detectedCharset);
			if (responseText != null) {
				charset = detectedCharset;
				return responseText;
			}
		}

		// As a last resort try our fallback charsets to decode the data.
		for (String charset : FALLBACK_CHARSETS) {
			responseText = decodeResponseData(charset);
			if (responseText != null) {
				return responseText;
			}
		}

		Log.e(TAG, "Could not decode response text.");
		return responseText;
	}

	public TiBlob getResponseData()
	{
		return responseData;
	}

	public DocumentProxy getResponseXML()
	{
		// avoid eating up tons of memory if we have a large binary data blob
		if (TiMimeTypeHelper.isBinaryMimeType(contentType))
		{
			return null;
		}

		if (responseXml == null && (responseData != null || responseText != null)) {
			try {
				String text = getResponseText();
				if (text == null || text.length() == 0) {
					return null;
				}

				if (charset != null && charset.length() > 0) {
					responseXml = XMLModule.parse(text, charset);

				} else {
					responseXml = XMLModule.parse(text);
				}

			} catch (Exception e) {
				Log.e(TAG, "Error parsing XML", e);
			}
		}

		return responseXml;
	}

	public void setResponseText(String responseText)
	{
		this.responseText = responseText;
	}

	public int getStatus()
	{
		return status;
	}

	public  void setStatus(int status)
	{
		this.status = status;
	}

	public  String getStatusText()
	{
		return statusText;
	}

	public  void setStatusText(String statusText)
	{
		this.statusText = statusText;
	}

	public void abort()
	{
		if (readyState > READY_STATE_UNSENT && readyState < READY_STATE_DONE) {
			aborted = true;
			if (client != null) {
				client.getConnectionManager().shutdown();
				client = null;
			}
			// Fire the disposehandle event if the request is aborted.
			// And it will dispose the handle of the httpclient in the JS.
			proxy.fireEvent(TiC.EVENT_DISPOSE_HANDLE, null);
		}
	}

	public String getAllResponseHeaders()
	{
		String result = "";
		Header[] theHeaders = null;
		if (response != null)  {
			theHeaders = response.getAllHeaders();
		} else {
			theHeaders = responseHeaders;
		}
		if (theHeaders != null) {
			StringBuilder sb = new StringBuilder(256);
			for (Header h : theHeaders) {
				sb.append(h.getName()).append(":").append(h.getValue()).append("\n");
			}
			result = sb.toString();
		}
		return result;
	}

	public void clearCookies(String url)
	{
		if (client == null) {
			client = createClient();
		}
		List<Cookie> cookies = new ArrayList<Cookie>(client.getCookieStore().getCookies());
		client.getCookieStore().clear();
		String lower_url = url.toLowerCase();

		for (Cookie cookie : cookies) {
			if (!lower_url.contains(cookie.getDomain().toLowerCase())) {
				client.getCookieStore().addCookie(cookie);
			}
		}
	}

	public void setRequestHeader(String header, String value)
	{
		if (readyState <= READY_STATE_OPENED) {
			headers.put(header, value);

		} else {
			throw new IllegalStateException("setRequestHeader can only be called before invoking send.");
		}
	}

	public String getResponseHeader(String headerName)
	{
		String result = "";
		Header[] theHeaders = null;
		if (response != null)  {
			theHeaders = response.getAllHeaders();
		} else {
			theHeaders = responseHeaders;
		}
		if(theHeaders != null) {
			boolean firstPass = true;
			StringBuilder sb = new StringBuilder(32);
			for (Header h : theHeaders) {
				if (h.getName().equalsIgnoreCase(headerName)) {
					if (!firstPass) {
						sb.append(", ");
					}
					sb.append(h.getValue());
					firstPass = false;
				}
			}
			result = sb.toString();

			if (result.length() == 0) {
				Log.w(TAG, "No value for response header: " + headerName, Log.DEBUG_MODE);
			}

		}

		return result;
	}

	public void open(String method, String url)
	{
		Log.d(TAG, "open request method=" + method + " url=" + url, Log.DEBUG_MODE);

		if (url == null)
		{
			Log.e(TAG, "Unable to open a null URL");
			throw new IllegalArgumentException("URL cannot be null");
		}

		// if the url is not prepended with either http or
		// https, then default to http and prepend the protocol
		// to the url
		String lowerCaseUrl = url.toLowerCase();
		if (!lowerCaseUrl.startsWith("http://") && !lowerCaseUrl.startsWith("https://")) {
			url = "http://" + url;
		}

		if (autoEncodeUrl) {
			this.uri = TiUrl.getCleanUri(url);

		} else {
			this.uri = Uri.parse(url);
		}

		// If the original url does not contain any
		// escaped query string (i.e., does not look
		// pre-encoded), go ahead and reset it to the
		// clean uri. Else keep it as is so the user's
		// escaping stays in effect.  The users are on their own
		// at that point.
		if (autoEncodeUrl && !url.matches(".*\\?.*\\%\\d\\d.*$")) {
			this.url = this.uri.toString();

		} else {
			this.url = url;
		}

		redirectedLocation = null;
		this.method = method;
		String hostString = uri.getHost();
		int port = PROTOCOL_DEFAULT_PORT;

		// The Android Uri doesn't seem to handle user ids with at-signs (@) in them
		// properly, even if the @ is escaped.  It will set the host (uri.getHost()) to
		// the part of the user name after the @.  For example, this Uri would get
		// the host set to appcelerator.com when it should be mickey.com:
		// http://testuser@appcelerator.com:password@mickey.com/xx
		// ... even if that first one is escaped to ...
		// http://testuser%40appcelerator.com:password@mickey.com/xx
		// Tests show that Java URL handles it properly, however.  So revert to using Java URL.getHost()
		// if we see that the Uri.getUserInfo has an at-sign in it.
		// Also, uri.getPort() will throw an exception as it will try to parse what it thinks is the port
		// part of the Uri (":password....") as an int.  So in this case we'll get the port number
		// as well from Java URL.  See Lighthouse ticket 2150.
		if (uri.getUserInfo() != null && uri.getUserInfo().contains("@")) {
			URL javaUrl;
			try {
				javaUrl = new URL(uri.toString());
				hostString = javaUrl.getHost();
				port = javaUrl.getPort();

			} catch (MalformedURLException e) {
				Log.e(TAG, "Error attempting to derive Java url from uri: " + e.getMessage(), e);
			}

		} else {
			port = uri.getPort();
		}

		Log.d(
			TAG,
			"Instantiating host with hostString='" + hostString + "', port='" + port + "', scheme='" + uri.getScheme() + "'",
			Log.DEBUG_MODE);

		host = new HttpHost(hostString, port, uri.getScheme());
		if (uri.getUserInfo() != null) {
			credentials = new UsernamePasswordCredentials(uri.getUserInfo());
		}
		if (credentials == null) {
			String userName = ((HTTPClientProxy)proxy).getUsername();
			String password = ((HTTPClientProxy)proxy).getPassword();
			String domain = ((HTTPClientProxy)proxy).getDomain();
			if (domain != null) {
				password = (password == null)?"":password;
				credentials = new NTCredentials(userName, password, TiPlatformHelper.getInstance().getMobileId(), domain);
			}
			else {
				if (userName != null) {
					password = (password == null)?"":password;
					credentials = new UsernamePasswordCredentials(userName, password);
				}
			}
		}
		setReadyState(READY_STATE_OPENED);
		setRequestHeader("User-Agent", TITANIUM_USER_AGENT);
		// Causes Auth to Fail with twitter and other size apparently block X- as well
		// Ticket #729, ignore twitter for now
		if (!hostString.contains("twitter.com")) {
			setRequestHeader("X-Requested-With","XMLHttpRequest");

		} else {
			Log.i(TAG, "Twitter: not sending X-Requested-With header", Log.DEBUG_MODE);
		}
	}

	public void setRawData(Object data)
	{
		this.data = data;
	}

	public void addPostData(String name, String value)
	{
		if (value == null) {
			value = "";
		}
		try {
			if (needMultipart) {
				// JGH NOTE: this seems to be a bug in RoR where it would puke if you
				// send a content-type of text/plain for key/value pairs in form-data
				// so we send an empty string by default instead which will cause the
				// StringBody to not include the content-type header. this should be
				// harmless for all other cases
				parts.put(name, new StringBody(value,"",null));

			} else {
				nvPairs.add(new BasicNameValuePair(name, value.toString()));
			}

		} catch (UnsupportedEncodingException e) {
			nvPairs.add(new BasicNameValuePair(name, value.toString()));
		}
	}

	private void dispatchCallback(String name, KrollDict data) {
		if (data == null) {
			data = new KrollDict();
		}

		data.put("source", proxy);

		proxy.callPropertyAsync(name, new Object[] { data });
	}

	private int addTitaniumFileAsPostData(String name, Object value)
	{
		try {
			// TiResourceFile cannot use the FileBody approach directly, because it requires
			// a java File object, which you can't get from packaged resources. So
			// TiResourceFile uses the approach we use for blobs, which is write out the
			// contents to a temp file, then use that for the FileBody.
			if (value instanceof TiBaseFile && !(value instanceof TiResourceFile)) {
				TiBaseFile baseFile = (TiBaseFile) value;
				FileBody body = new FileBody(baseFile.getNativeFile(), TiMimeTypeHelper.getMimeType(baseFile.nativePath()));
				parts.put(name, body);
				return (int)baseFile.getNativeFile().length();

			} else if (value instanceof TiBlob || value instanceof TiResourceFile) {
				TiBlob blob;
				if (value instanceof TiBlob) {
					blob = (TiBlob) value;
				} else {
					blob = ((TiResourceFile) value).read();
				}
				String mimeType = blob.getMimeType();
				File tmpFile = File.createTempFile("tixhr", "." + TiMimeTypeHelper.getFileExtensionFromMimeType(mimeType, "txt"));
				FileOutputStream fos = new FileOutputStream(tmpFile);
				if (blob.getType() == TiBlob.TYPE_STREAM_BASE64) {
					TiBaseFile.copyStream(blob.getInputStream(), new Base64OutputStream(fos, android.util.Base64.DEFAULT));
				} else {
					fos.write(blob.getBytes());
				}
				fos.close();

				tmpFiles.add(tmpFile);

				FileBody body = new FileBody(tmpFile, mimeType);
				parts.put(name, body);
				return (int)tmpFile.length();

			} else {
				if (value != null) {
					Log.e(TAG, name + " is a " + value.getClass().getSimpleName());

				} else {
					Log.e(TAG, name + " is null");
				}
			}

		} catch (IOException e) {
			Log.e(TAG, "Error adding post data ("+name+"): " + e.getMessage());
		}
		return 0;
	}

	private Object titaniumFileAsPutData(Object value)
	{
		if (value instanceof TiBaseFile && !(value instanceof TiResourceFile)) {
			TiBaseFile baseFile = (TiBaseFile) value;
			return new FileEntity(baseFile.getNativeFile(), TiMimeTypeHelper.getMimeType(baseFile.nativePath()));
		} else if (value instanceof TiBlob || value instanceof TiResourceFile) {
			try {
				TiBlob blob;
				if (value instanceof TiBlob) {
					blob = (TiBlob) value;
				} else {
					blob = ((TiResourceFile) value).read();
				}
				String mimeType = blob.getMimeType();
				File tmpFile = File.createTempFile("tixhr", "." + TiMimeTypeHelper.getFileExtensionFromMimeType(mimeType, "txt"));
				FileOutputStream fos = new FileOutputStream(tmpFile);
				fos.write(blob.getBytes());
				fos.close();

				tmpFiles.add(tmpFile);
				return new FileEntity(tmpFile, mimeType);
			} catch (IOException e) {
				Log.e(TAG, "Error adding put data: " + e.getMessage());
			}
		}
		return value;
	}

	protected DefaultHttpClient createClient()
	{
		SchemeRegistry registry = new SchemeRegistry();
		registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));

		HttpParams params = new BasicHttpParams();
		ConnManagerParams.setMaxTotalConnections(params, 5);
		ConnPerRouteBean connPerRoute = new ConnPerRouteBean(5);
		ConnManagerParams.setMaxConnectionsPerRoute(params, connPerRoute);

		HttpProtocolParams.setUseExpectContinue(params, false);
		HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);

		DefaultHttpClient httpClient = new DefaultHttpClient(new ThreadSafeClientConnManager(params, registry), params);
		httpClient.setCookieStore(cookieStore);

		return httpClient;
	}

	protected DefaultHttpClient getClient(boolean validating)
	{
		SSLSocketFactory sslSocketFactory = null;

		if (this.securityManager != null) {
			if (this.securityManager.willHandleURL(this.uri)) {
				TrustManager[] trustManagerArray = this.securityManager.getTrustManagers((HTTPClientProxy)this.proxy);
				KeyManager[] keyManagerArray = this.securityManager.getKeyManagers((HTTPClientProxy)this.proxy);

				try {
					sslSocketFactory = new TiSocketFactory(keyManagerArray, trustManagerArray, tlsVersion);
				} catch(Exception e) {
					Log.e(TAG, "Error creating SSLSocketFactory: " + e.getMessage());
					sslSocketFactory = null;
				}
			}
		}
		if (sslSocketFactory == null) {
			if (trustManagers.size() > 0 || keyManagers.size() > 0) {
				TrustManager[] trustManagerArray = null;
				KeyManager[] keyManagerArray = null;

				if (trustManagers.size() > 0) {
					trustManagerArray = new X509TrustManager[trustManagers.size()];
					trustManagerArray = trustManagers.toArray(trustManagerArray);
				}

				if (keyManagers.size() > 0) {
					keyManagerArray = new X509KeyManager[keyManagers.size()];
					keyManagerArray = keyManagers.toArray(keyManagerArray);
				}

				try {
					sslSocketFactory = new TiSocketFactory(keyManagerArray, trustManagerArray, tlsVersion);
				} catch(Exception e) {
					Log.e(TAG, "Error creating SSLSocketFactory: " + e.getMessage());
					sslSocketFactory = null;
				}
			} else if (!validating) {
				TrustManager trustManagerArray[] = new TrustManager[] { new NonValidatingTrustManager() };
				try {
					sslSocketFactory = new TiSocketFactory(null, trustManagerArray, tlsVersion);
				} catch(Exception e) {
					Log.e(TAG, "Error creating SSLSocketFactory: " + e.getMessage());
					sslSocketFactory = null;
				}
			}
		}

		if (client == null) {
			client = createClient();
		}

		if (sslSocketFactory != null) {
			client.getConnectionManager().getSchemeRegistry().register(new Scheme("https", sslSocketFactory, 443));
		} else if (!validating) {
			client.getConnectionManager().getSchemeRegistry().register(new Scheme("https", new NonValidatingSSLSocketFactory(), 443));
		} else {
			try {
				client.getConnectionManager().getSchemeRegistry().register(new Scheme("https", new TLSSNISocketFactory(tlsVersion), 443));
			} catch (Exception e) {
				Log.e(TAG, "Error creating TLSSNISocketFactory: " + e.getMessage());
			}
		}

		return client;
	}

	public void send(Object userData) throws MethodNotSupportedException
	{
		aborted = false;

		// TODO consider using task manager
		int totalLength = 0;
		needMultipart = false;

		if (userData != null)
		{
			if (userData instanceof HashMap) {
				HashMap<String, Object> data = (HashMap) userData;
				boolean isPostOrPutOrPatch = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
				boolean isGet = !isPostOrPutOrPatch && method.equals("GET");

				// first time through check if we need multipart for POST
				for (String key : data.keySet()) {
					Object value = data.get(key);

					if(value != null) {
						// if the value is a proxy, we need to get the actual file object
						if (value instanceof TiFileProxy) {
							value = ((TiFileProxy) value).getBaseFile();
						}

						if (value instanceof TiBaseFile || value instanceof TiBlob) {
							needMultipart = true;
							break;
						}
					}
				}

				boolean queryStringAltered = false;
				for (String key : data.keySet()) {
					Object value = data.get(key);
					if (isPostOrPutOrPatch && (value != null)) {
						// if the value is a proxy, we need to get the actual file object
						if (value instanceof TiFileProxy) {
							value = ((TiFileProxy) value).getBaseFile();
						}

						if (value instanceof TiBaseFile || value instanceof TiBlob) {
							totalLength += addTitaniumFileAsPostData(key, value);

						} else {
							String str = TiConvert.toString(value);
							addPostData(key, str);
							totalLength += str.length();
						}

					} else if (isGet) {
						uri = uri.buildUpon().appendQueryParameter(
							key, TiConvert.toString(value)).build();
						queryStringAltered = true;
					}
				}

				if (queryStringAltered) {
					this.url = uri.toString();
				}
			} else if (userData instanceof TiFileProxy || userData instanceof TiBaseFile || userData instanceof TiBlob) {
				Object value = userData;
				if (value instanceof TiFileProxy) {
					value = ((TiFileProxy) value).getBaseFile();
				}
				if (value instanceof TiBaseFile || value instanceof TiBlob) {
					setRawData(titaniumFileAsPutData(value));
				} else {
					setRawData(TiConvert.toString(value));
				}
			} else {
				setRawData(TiConvert.toString(userData));
			}
		}

		Log.d(TAG, "Instantiating http request with method='" + method + "' and this url:", Log.DEBUG_MODE);
		Log.d(TAG, this.url, Log.DEBUG_MODE);

		request = new TiDefaultHttpRequestFactory().newHttpRequest(method, this.url);
		request.setHeader(TITANIUM_ID_HEADER, TiApplication.getInstance().getAppGUID());
		for (String header : headers.keySet()) {
			request.setHeader(header, headers.get(header));
		}

		clientThread = new Thread(new ClientRunnable(totalLength), "TiHttpClient-" + httpClientThreadCounter.incrementAndGet());
		clientThread.setPriority(Thread.MIN_PRIORITY);
		clientThread.start();

		Log.d(TAG, "Leaving send()", Log.DEBUG_MODE);
	}

	private class ClientRunnable implements Runnable
	{
		private final int totalLength;

		public ClientRunnable(int totalLength)
		{
			this.totalLength = totalLength;
		}

		public void run()
		{
			try {
				Thread.sleep(10);
				Log.d(TAG, "send()", Log.DEBUG_MODE);

				handler = new LocalResponseHandler(TiHTTPClient.this);

				// lazy get client each time in case the validatesSecureCertificate() changes
				client = getClient(validatesSecureCertificate());

				//If there are any custom authentication factories registered with the client add them here
				Enumeration<String> authSchemes = customAuthenticators.keys();
				while (authSchemes.hasMoreElements()) {
					String scheme = authSchemes.nextElement();
					client.getAuthSchemes().register(scheme, customAuthenticators.get(scheme));
				}

				if (credentials != null) {
					client.getCredentialsProvider().setCredentials (new AuthScope(uri.getHost(), -1), credentials);
					credentials = null;
				}
				client.setRedirectHandler(new RedirectHandler());
				if(request instanceof BasicHttpEntityEnclosingRequest) {

					UrlEncodedFormEntity form = null;
					MultipartEntity mpe = null;

					if (nvPairs.size() > 0) {
						try {
							form = new UrlEncodedFormEntity(nvPairs, "UTF-8");

						} catch (UnsupportedEncodingException e) {
							Log.e(TAG, "Unsupported encoding: ", e);
						}
					}

					if (parts.size() > 0 && needMultipart) {
						mpe = new MultipartEntity();
						for(String name : parts.keySet()) {
							Log.d(TAG, "adding part " + name + ", part type: " + parts.get(name).getMimeType() + ", len: "
								+ parts.get(name).getContentLength(), Log.DEBUG_MODE);
							mpe.addPart(name, parts.get(name));
						}

						if (form != null) {
							try {
								ByteArrayOutputStream bos = new ByteArrayOutputStream((int) form.getContentLength());
								form.writeTo(bos);
								mpe.addPart("form", new StringBody(bos.toString(), "application/x-www-form-urlencoded", Charset.forName("UTF-8")));

							} catch (UnsupportedEncodingException e) {
								Log.e(TAG, "Unsupported encoding: ", e);

							} catch (IOException e) {
								Log.e(TAG, "Error converting form to string: ", e);
							}
						}

						HttpEntityEnclosingRequest e = (HttpEntityEnclosingRequest) request;

						ProgressEntity progressEntity = new ProgressEntity(mpe, new ProgressListener() {
							public void progress(int progress) {
								KrollDict data = new KrollDict();
								data.put("progress", ((double)progress)/totalLength);
								dispatchCallback("onsendstream", data);
							}
						});
						e.setEntity(progressEntity);

						e.addHeader("Length", totalLength+"");
						//For multipart, the content-type and boundary will be set by the entity
						request.removeHeaders(HTTP.CONTENT_TYPE);

					} else {
						handleURLEncodedData(form);
					}

					//Remove Content-Length header if entity is set since setEntity implicitly sets Content-Length
					HttpEntityEnclosingRequest enclosingEntity = (HttpEntityEnclosingRequest) request;
					if (enclosingEntity.getEntity() != null) {
						request.removeHeaders("Content-Length");
					}
				}

				// set request specific parameters
				if (timeout != -1) {
					HttpConnectionParams.setConnectionTimeout(request.getParams(), timeout);
					HttpConnectionParams.setSoTimeout(request.getParams(), timeout);
				}

				Log.d(TAG, "Preparing to execute request", Log.DEBUG_MODE);

				String result = null;
				if (aborted) {
					return;
				}
				try {
					result = client.execute(host, request, handler);
				} catch (IOException e) {
					if (!aborted) {
						throw e;
					}
				}

				if(result != null) {
					Log.d(TAG, "Have result back from request len=" + result.length(), Log.DEBUG_MODE);
				}
				connected = false;
				setResponseText(result);

				if (responseStatusLine.getStatusCode() >= 400) {
					throw new HttpResponseException(responseStatusLine.getStatusCode(), responseStatusLine.getReasonPhrase());
				}

				if (!aborted) {
					setReadyState(READY_STATE_DONE);
				}

			} catch(Throwable t) {
				if (client != null) {
					Log.d(TAG, "clearing the expired and idle connections", Log.DEBUG_MODE);
					client.getConnectionManager().closeExpiredConnections();
					client.getConnectionManager().closeIdleConnections(0, TimeUnit.NANOSECONDS);

				} else {
					Log.d(TAG, "client is not valid, unable to clear expired and idle connections");
				}

				String msg = t.getMessage();
				if (msg == null && t.getCause() != null) {
					msg = t.getCause().getMessage();
				}
				if (msg == null) {
					msg = t.getClass().getName();
				}
				Log.e(TAG, "HTTP Error (" + t.getClass().getName() + "): " + msg, t);

				KrollDict data = new KrollDict();
				data.putCodeAndMessage(TiC.ERROR_CODE_UNKNOWN, msg);
				dispatchCallback("onerror", data);
			} finally {
				deleteTmpFiles();

				//Clean up response,request,client,handler and clientThread
				if(response != null) {
					responseHeaders = response.getAllHeaders();
					response = null;
				}

				request = null;
				handler = null;
				client = null;
				clientThread = null;

				// Fire the disposehandle event if the request is finished successfully or the errors occur.
				// And it will dispose the handle of the httpclient in the JS.
				proxy.fireEvent(TiC.EVENT_DISPOSE_HANDLE, null);
			}

		}
	}

	private void deleteTmpFiles()
	{
		if (tmpFiles.isEmpty()) {
			return;
		}

		for (File tmpFile : tmpFiles) {
			tmpFile.delete();
		}
		tmpFiles.clear();
	}

	private void handleURLEncodedData(UrlEncodedFormEntity form)
	{
		AbstractHttpEntity entity = null;
		if (data instanceof String) {
			try {
				entity = new StringEntity((String) data, "UTF-8");

			} catch(Exception ex) {
				//FIXME
				Log.e(TAG, "Exception, implement recovery: ", ex);
			}
		} else if (data instanceof AbstractHttpEntity) {
			entity = (AbstractHttpEntity) data;
		} else {
			entity = form;
		}

		if (entity != null) {
			Header header = request.getFirstHeader("Content-Type");
			if(header == null) {
				entity.setContentType("application/x-www-form-urlencoded");

			} else {
				entity.setContentType(header.getValue());
			}
			HttpEntityEnclosingRequest e = (HttpEntityEnclosingRequest)request;
			e.setEntity(entity);
		}
	}

	public String getLocation()
	{
		if (redirectedLocation != null) {
			return redirectedLocation;
		}
		return url;
	}

	public String getConnectionType()
	{
		return method;
	}

	public boolean isConnected()
	{
		return connected;
	}

	public void setTimeout(int millis)
	{
		timeout = millis;
	}

	protected void setAutoEncodeUrl(boolean value)
	{
		autoEncodeUrl = value;
	}

	protected boolean getAutoEncodeUrl()
	{
		return autoEncodeUrl;
	}

	protected void setAutoRedirect(boolean value)
	{
		autoRedirect = value;
	}

	protected boolean getAutoRedirect()
	{
		return autoRedirect;
	}

	protected void addKeyManager(X509KeyManager manager)
	{
		if (Log.isDebugModeEnabled()) {
			Log.d(TAG, "addKeyManager method is deprecated. Use the securityManager property on the HttpClient to define custom SSL Contexts", Log.DEBUG_MODE);
		}
		keyManagers.add(manager);
	}

	protected void addTrustManager(X509TrustManager manager)
	{
		if (Log.isDebugModeEnabled()) {
			Log.d(TAG, "addTrustManager method is deprecated. Use the securityManager property on the HttpClient to define custom SSL Contexts", Log.DEBUG_MODE);
		}
		trustManagers.add(manager);
	}

	protected void setTlsVersion(int value)
	{
		this.proxy.setProperty(TiC.PROPERTY_TLS_VERSION, value);
		tlsVersion = value;
	}

}
