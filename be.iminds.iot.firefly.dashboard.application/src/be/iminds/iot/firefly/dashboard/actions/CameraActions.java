package be.iminds.iot.firefly.dashboard.actions;

import java.io.IOException;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import javax.servlet.AsyncContext;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import aQute.lib.collections.MultiMap;
import be.iminds.iot.firefly.dashboard.Actions;
import be.iminds.iot.things.api.Thing;
import be.iminds.iot.things.api.camera.Camera;
import be.iminds.iot.things.api.camera.Camera.Format;
import be.iminds.iot.things.api.camera.CameraListener;

@Component(service={Servlet.class,Actions.class},
		property={"aiolos.proxy=false","alias=/be.iminds.iot.firefly/camera.mjpeg"})
public class CameraActions extends HttpServlet implements Actions, CameraListener {

	private Map<UUID, Camera> cameras = Collections.synchronizedMap(new HashMap<UUID, Camera>());
	
	private MultiMap<UUID, CameraStream> streamsByCameraId = new MultiMap<>();
	private Map<String, CameraStream> streamsByClient = new HashMap<>();

	private Map<UUID, ServiceRegistration> listenerRegistrations = new HashMap<>();
	
	private BundleContext context;
	
	@Activate
	public void activate(BundleContext ctx){
		this.context = ctx;
	}
	
	@Override
	public String getType() {
		return "camera";
	}

	@Override
	public void action(UUID id, String... params) {
		Camera camera = cameras.get(id);
		if(camera!=null){
			if(params.length==0){
				// default action - switch camera on/off
				if(camera.isOn()){
					camera.stop();
				} else {
					camera.start();
				}
			}
		}
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		String targetId = request.getParameter("id");
		
		if(targetId==null){
			System.err.println("No id provided");
			return;
		}
		
		UUID id = null;
		try {
			id = UUID.fromString(targetId);
		} catch(IllegalArgumentException e){
			System.err.println("No valid id "+targetId);
			return;
		}
		
		if(!cameras.containsKey(id)){
			System.err.println("Camera "+id+" not available");
			return;
		}
		
		response.setHeader("Cache-Control", "no-cache");
		response.setCharacterEncoding("UTF-8");
		response.addHeader("Connection", "keep-alive");
		response.setContentType("multipart/x-mixed-replace;boundary=next");

		// check if there is already a stream for this client
		String client = request.getRemoteHost()+":"+request.getRemotePort();
		CameraStream stream = streamsByClient.get(client);
		if(stream==null){
			stream = new CameraStream(id, client);
			synchronized(streamsByCameraId){
				streamsByCameraId.add(id, stream);
				streamsByClient.put(client, stream);
			}
		}
		
		stream.updateRequest(request);

		// if not yet a stream for this id, register CameraListener service
		if(!listenerRegistrations.containsKey(id)){
			Dictionary<String, Object> properties = new Hashtable<>();
			properties.put("aiolos.unique", true);
			properties.put("be.iminds.iot.thing.camera.id", id.toString());
			ServiceRegistration r = context.registerService(CameraListener.class, this, properties);
			listenerRegistrations.put(id, r);
		}
		

	}
	
	@Reference(cardinality=ReferenceCardinality.MULTIPLE,
			policy=ReferencePolicy.DYNAMIC)
	public void addCamera(Camera c, Map<String, Object> properties){
		UUID id = (UUID) properties.get(Thing.ID);
		cameras.put(id, c);
	}
	
	public void removeCamera(Camera c, Map<String, Object> properties){
		UUID id = (UUID) properties.get(Thing.ID);
		cameras.remove(id);
	}

	@Override
	public void nextFrame(UUID id, Format format, byte[] data) {
		if(format!=Format.MJPEG){
			// only send MJPEG frames
			return;
		}
		synchronized(streamsByCameraId){
			Iterator<CameraStream> it = streamsByCameraId.get(id).iterator();
			while(it.hasNext()){
				CameraStream s = it.next();
				try {
					s.sendFrame(data);
				} catch(IOException e){
					streamsByClient.remove(s.getClient());
					it.remove();
					s.close();
				}
			}
			if(streamsByCameraId.get(id)==null || streamsByCameraId.get(id).isEmpty()){
				ServiceRegistration r = listenerRegistrations.remove(id);
				if(r!=null){
					r.unregister();
				}
			}
		}
	}


	
	private class CameraStream {
		
		private final UUID target;
		private final String client;
		
		private AsyncContext async;
		private ServletResponse response; 

		
		public CameraStream(UUID id, String client){
			this.target = id;
			this.client = client;
		}
		
		protected void sendFrame(byte[] data) throws IOException {
			response.getOutputStream().println("--next");
			response.getOutputStream().println("Content-Type: image/jpeg");
			response.getOutputStream().println("Content-Length: "+data.length);
			response.getOutputStream().println("");
			response.getOutputStream().write(data, 0, data.length);
			response.getOutputStream().println("");
			response.flushBuffer();
		}
		
		protected String getClient(){
			return client;
		}
		
		protected void updateRequest(ServletRequest request){
			this.async = request.startAsync();
			this.response = async.getResponse();
		}
		
		protected void close(){
			this.async.complete();
		}
	}
}
