package ch.nksa.pu.robotics.libs;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import ch.nksa.pu.robotics.libs.IncomingRequest;

import lejos.nxt.comm.BTConnection;
import lejos.nxt.comm.Bluetooth;

public class Uplink {
	protected BTConnection uplink;
	protected DataInputStream dis;
	protected DataOutputStream dos;
	protected Thread receivingThread;
	protected Thread sendingThread;
	protected static Uplink instance;
	protected volatile ArrayList<IncomingRequest> listeners = new ArrayList<IncomingRequest>();
	protected ArrayList<byte[][]> rawIncoming = new ArrayList<byte[][]>();
	protected ArrayList<IncomingRequest> incomingRequests = new ArrayList<IncomingRequest>();
	protected ArrayList<OutgoingRequest> outgoingRequests = new ArrayList<OutgoingRequest>();
	protected int requestsSent = 0;
	/**
	 * Holds a 2d byte array with the latest incoming request
	 */
	protected RequestStruct requestStruct;
	
	protected Uplink(boolean connect_now){
		if(connect_now){
			connect(10 * 1000);
		}
	}
	
	//Singleton Patterns
	public static Uplink getInstance(){
		return getInstance(false);
	}
	
	public static Uplink getInstance(boolean connect_now){
		if(instance == null){
			instance = new Uplink(connect_now);
		}
		return instance;
	}
	//end Singleton
	
	public boolean connect(int timeout){
		System.out.println("Waiting for connection...");
		uplink = Bluetooth.waitForConnection();
		dis = uplink.openDataInputStream();
		dos = uplink.openDataOutputStream();
		System.out.println("Connection established.");
		sendingThread = new Thread(){
			public void run(){
				sendRequests();
			}
		};
		sendingThread.start();
		
		receivingThread = new Thread(){
			public void run(){
				getRequests();
			}
		};
		receivingThread.start();
		return true;
	}
	
	public synchronized void registerRequest(OutgoingRequest req){
		req.id = outgoingRequests.size();
		outgoingRequests.add(req);
	}
	
	public void registerListener(IncomingRequest l){
		System.out.println("Adding listener to Uplink.");
		synchronized(listeners){
			listeners.add(l);
		}
		System.out.println("Active listeners: " + listeners.size());
	}
	
	public OutgoingRequest getOutgoingRequest(int id){
		if(outgoingRequests.size() > id){
			return outgoingRequests.get(id);
		}
		return null;
	}
	
	public IncomingRequest getIncomingRequest(int id){
		if(incomingRequests.size() > id){
			return incomingRequests.get(id);
		}
		return null;
	}
	
	
	/**
	 * must NOT be invoked manually!
	 */
	protected void sendRequests(){
		while(true){
			if(outgoingRequests.size() > requestsSent){
				OutgoingRequest req = outgoingRequests.get(requestsSent);
				try {
					/**
					 * Request Protocol Format
					 * 
					 *id
					 * RequestMode
					 * ReferenceId
					 * Sender
   					 * Nick
					 * Subject
					 */
					byte[][] header;
					header = req.getHeader();
					byte[][] data;
					data = req.getData();
					dos.writeInt(header.length + data.length);
					for(byte[] b: header){
						dos.writeInt(b.length);
						dos.write(b);
					}
					
					for(byte[] b: data){
						dos.writeInt(b.length);
						dos.write(b);
					}
					dos.flush();
					req.hasBeenSent = true;
					requestsSent ++;
				} catch (IOException e) {}
				
			}
			else{
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {}
			}
		}
	}
	
	
	/**
	 * must NOT be invoked manually!
	 */
	protected void getRequests(){
		System.out.println("Listening...");
		Thread waiting;
		int length = 0;
		receiving:
		while(true){
			try {
				System.out.println("Waiting for request ("+incomingRequests.size()+ ")...");
				int lines = dis.readInt();
				System.out.println("Expecting " + lines + " lines.");
				byte[][] incoming = new byte[lines][];
				for(int i = 0; i < lines; i++){
					length = dis.readInt();
					incoming[i] = new byte[length];
					dis.readFully(incoming[i], 0, length);
				}
				
				if(!IncomingRequest.headerIsValid(incoming)){
					//Add bogus object in order to keep index and id in sync
					incomingRequests.add(null);
					System.out.println(">Unexpected behaviour may follow!");
					continue;
				}
				System.out.println("Synchronizing...");
				synchronized (listeners) {
					System.out.println("Synchronized.");
					this.requestStruct = new RequestStruct(incoming);
					System.out.println("Passing request " + this.requestStruct.sender + " to " + listeners.size() + " Listeners.");
					for(IncomingRequest l: listeners){
						IncomingRequest req = l.validate(requestStruct);
						if(req != null){
							incomingRequests.add(req);
							continue receiving;
						}
					}
				}
				System.out.println("No listener has been found. Passing to BasicRequest.");
				BasicIncomingRequest req =  BasicIncomingRequest.validate(incoming);
				incomingRequests.add(req);
			} catch (IOException e) {
				System.out.println("Bluetooth has been terminated unexpectedly.");
				break;
			}
		}
	}
}
