package org.eclipse.jetty.fcgi.client.http;

import java.net.URI;
import java.util.Locale;

import org.eclipse.jetty.client.HttpChannel;
import org.eclipse.jetty.client.HttpContent;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpSender;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.ClientGenerator;
import org.eclipse.jetty.fcgi.generator.Generator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Jetty;

public class HttpSenderOverFCGI extends HttpSender
{
    private final ClientGenerator generator;

    public HttpSenderOverFCGI(HttpChannel channel)
    {
        super(channel);
        this.generator = new ClientGenerator(channel.getHttpDestination().getHttpClient().getByteBufferPool());
    }

    @Override
    protected HttpChannelOverFCGI getHttpChannel()
    {
        return (HttpChannelOverFCGI)super.getHttpChannel();
    }

    @Override
    protected void sendHeaders(HttpExchange exchange, HttpContent content, Callback callback)
    {
        Request request = exchange.getRequest();
        // Copy the request headers to be able to convert them properly
        HttpFields headers = new HttpFields();
        for (HttpField field : request.getHeaders())
            headers.put(field);
        HttpFields fcgiHeaders = new HttpFields();

        // FastCGI headers based on the URI
        URI uri = request.getURI();
        String path = uri.getPath();
        fcgiHeaders.put(FCGI.Headers.REQUEST_URI, path);
        String query = uri.getQuery();
        fcgiHeaders.put(FCGI.Headers.QUERY_STRING, query == null ? "" : query);
        int lastSegment = path.lastIndexOf('/');
        String scriptName = lastSegment < 0 ? path : path.substring(lastSegment);
        fcgiHeaders.put(FCGI.Headers.SCRIPT_NAME, scriptName);

        // FastCGI headers based on HTTP headers
        HttpField httpField = headers.remove(HttpHeader.AUTHORIZATION);
        if (httpField != null)
            fcgiHeaders.put(FCGI.Headers.AUTH_TYPE, httpField.getValue());
        httpField = headers.remove(HttpHeader.CONTENT_LENGTH);
        fcgiHeaders.put(FCGI.Headers.CONTENT_LENGTH, httpField == null ? "" : httpField.getValue());
        httpField = headers.remove(HttpHeader.CONTENT_TYPE);
        fcgiHeaders.put(FCGI.Headers.CONTENT_TYPE, httpField == null ? "" : httpField.getValue());

        // FastCGI headers that are not based on HTTP headers nor URI
        fcgiHeaders.put(FCGI.Headers.REQUEST_METHOD, request.getMethod());
        fcgiHeaders.put(FCGI.Headers.SERVER_PROTOCOL, request.getVersion().asString());
        fcgiHeaders.put(FCGI.Headers.GATEWAY_INTERFACE, "CGI/1.1");
        fcgiHeaders.put(FCGI.Headers.SERVER_SOFTWARE, "Jetty/" + Jetty.VERSION);

        // Translate remaining HTTP header into the HTTP_* format
        for (HttpField field : headers)
        {
            String name = field.getName();
            String fcgiName = "HTTP_" + name.replaceAll("-", "_").toUpperCase(Locale.ENGLISH);
            fcgiHeaders.add(fcgiName, field.getValue());
        }

        // Give a chance to the transport implementation to customize the FastCGI headers
        HttpClientTransportOverFCGI transport = (HttpClientTransportOverFCGI)getHttpChannel().getHttpDestination().getHttpClient().getTransport();
        transport.customize(request, fcgiHeaders);

        int id = getHttpChannel().getRequest();
        boolean hasContent = content.hasContent();
        Generator.Result headersResult = generator.generateRequestHeaders(id, fcgiHeaders,
                hasContent ? callback : new Callback.Adapter());
        if (hasContent)
        {
            getHttpChannel().flush(headersResult);
        }
        else
        {
            Generator.Result noContentResult = generator.generateRequestContent(id, null, true, callback);
            getHttpChannel().flush(headersResult, noContentResult);
        }
    }

    @Override
    protected void sendContent(HttpExchange exchange, HttpContent content, Callback callback)
    {
        if (content.isConsumed())
        {
            callback.succeeded();
        }
        else
        {
            int request = getHttpChannel().getRequest();
            Generator.Result result = generator.generateRequestContent(request, content.getByteBuffer(), content.isLast(), callback);
            getHttpChannel().flush(result);
        }
    }
}
