package me.vzhilin.bstreamer.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspVersions;
import me.vzhilin.bstreamer.server.conf.Config;
import me.vzhilin.bstreamer.server.strategy.sync.GroupStreamer;
import me.vzhilin.bstreamer.server.streaming.file.SourceDescription;
import me.vzhilin.bstreamer.util.PropertyMap;
import org.apache.log4j.Logger;

import java.util.Base64;

public final class RtspServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger LOG = Logger.getLogger(RtspServerHandler.class);

    private Config config;
    private ServerContext context;

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        Channel ch = ctx.channel();
        context = ch.attr(RtspServerAttributes.CONTEXT).get();
        config = context.getConfig();
    }

    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        HttpMethod method = request.method();
        HttpResponse response;
        HttpHeaders headers = request.headers();
        switch (method.name()) {
            case "OPTIONS":
                response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, HttpResponseStatus.OK);
                response.headers().set(RtspHeaderNames.CSEQ, headers.get(RtspHeaderNames.CSEQ));
                response.headers().set(RtspHeaderNames.PUBLIC, "DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE");
                ctx.writeAndFlush(response);
                break;
            case "DESCRIBE": {
                RtspUriParser uri = new RtspUriParser(request.uri());
                if (uri.pathItems() < 1) {
                    response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, HttpResponseStatus.BAD_REQUEST);
                    response.headers().set(RtspHeaderNames.CSEQ, headers.get(RtspHeaderNames.CSEQ));
                    ctx.writeAndFlush(response);
                } else {
                    response = description(uri, getStreamerForUrl(ctx.channel().eventLoop(), uri).describe());
                    response.headers().set(RtspHeaderNames.CSEQ, headers.get(RtspHeaderNames.CSEQ));
                    ctx.writeAndFlush(response);
                }
                break;
            }
            case "SETUP": {
                response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, HttpResponseStatus.OK);
                response.headers().set(RtspHeaderNames.CSEQ, headers.get(RtspHeaderNames.CSEQ));
                response.headers().set(RtspHeaderNames.SESSION, "1234");
                response.headers().set(RtspHeaderNames.TRANSPORT, "RTP/AVP/TCP;unicast;interleaved=0-1");
                ctx.writeAndFlush(response);
                break;
            }
            case "PLAY": {
                RtspUriParser uri = new RtspUriParser(request.uri());
                if (uri.pathItems() < 1) {
                    response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, HttpResponseStatus.BAD_REQUEST);
                    response.headers().set(RtspHeaderNames.CSEQ, headers.get(RtspHeaderNames.CSEQ));
                    ctx.writeAndFlush(response);
                } else {
                    getStreamerForUrl(ctx.channel().eventLoop(), uri).attachContext(ctx);

                    response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, HttpResponseStatus.OK);
                    response.headers().set(RtspHeaderNames.CSEQ, headers.get(RtspHeaderNames.CSEQ));
                    response.headers().set(RtspHeaderNames.SESSION, headers.get(RtspHeaderNames.SESSION));
                    ctx.writeAndFlush(response);
                }

                break;
            }
        case "GET_PARAMETER": {
            response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, HttpResponseStatus.OK);
            response.headers().set(RtspHeaderNames.CSEQ, headers.get(RtspHeaderNames.CSEQ));
            response.headers().set(RtspHeaderNames.SESSION, headers.get(RtspHeaderNames.SESSION));
            response.headers().set(RtspHeaderNames.TRANSPORT, "RTP/AVP/TCP;unicast;interleaved=0-1");
            ctx.writeAndFlush(response);
            break;
        }
        default:
            response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, HttpResponseStatus.BAD_REQUEST);
            if (headers.contains(RtspHeaderNames.CSEQ)) {
                response.headers().set(RtspHeaderNames.CSEQ, headers.get(RtspHeaderNames.CSEQ));
            }

            ctx.writeAndFlush(response);
        }
    }

    private FullHttpResponse description(RtspUriParser uri, SourceDescription description) {
        byte[] sps = description.getSps();
        byte[] pps = description.getPps();
        String spsBase64 = Base64.getEncoder().encodeToString(sps);
        String ppsBase64 = Base64.getEncoder().encodeToString(pps);

        String profileLevelId = String.format("%02x%02x%02x", sps[0], sps[1], sps[2]);
        String sdpMessage = "v=0\r\n" +
                "o=RTSP 50539017935697 1 IN IP4 0.0.0.0\r\n" +
                "s=bserver\r\n" +
                "a=control:*\r\n" +
                "m=video 0 RTP/AVP 98\r\n" +
                "a=fmtp:98 sprop-parameter-sets=" +
                spsBase64 + "," + ppsBase64 + ";profile-level-id=" + profileLevelId
                + ";packetization-mode=1\r\n"
                + "a=rtpmap:98 H264/90000\r\n"
                + "a=control:TrackID=0\r\n";

        ByteBuf payload = ByteBufUtil.writeAscii(PooledByteBufAllocator.DEFAULT, sdpMessage);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, HttpResponseStatus.OK, payload);
        response.headers().add(HttpHeaderNames.CONTENT_LENGTH, payload.readableBytes());
        response.headers().add(HttpHeaderNames.CONTENT_BASE, uri.getUri());
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/sdp");
        return response;
    }

    private GroupStreamer getStreamerForUrl(EventLoop loop, RtspUriParser uri) {
        String url = uri.pathItem(0);

        PropertyMap mpsc = config.getStreamingConfig(url);
        PropertyMap conf = mpsc.getMap("conf");
        conf.putAll(uri.allParameters());
        String clazz = mpsc.getString("class");

        return context.getStreamer(loop, url, clazz, conf);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (LOG.isDebugEnabled()) {
            cause.printStackTrace();
        }
        ctx.close();
    }
}
