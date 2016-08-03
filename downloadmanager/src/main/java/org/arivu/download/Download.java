package org.arivu.download;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import org.arivu.download.Download.State;

public class Download {

	enum State{CREATED,RUNNING,ERRORED,COMPLETED,PAUSED}
	
//	private static final DownloadManager downloadManager = DownloadManager.instance;
	private static final Map<String,Download> downloadMap = new HashMap<>();
	
	public static Download get(String url){
		return downloadMap.get(url);
	}
	
	private static final String downloadFolder = "C:\\tmp\\download\\";
	static final int DOWNLOAD_THREAD_CNT = 5;
	private static final long segmentSize = 1024L*1024L*5L;
	
	private final long size;
	private final String hash256,url,fileName;
	private final boolean enableSegment;
	private final Collection<Segment> segments = new ArrayList<>();
	private final Collection<Segment> completedSegments = new ArrayList<>();
	private final Collection<Segment> errSegments = new ArrayList<>();
	
	private final String id = String.valueOf(System.currentTimeMillis());
	
	private final DownloadListener<Download,Collection<Throwable>> listener;
	
	private volatile State state = State.CREATED;
	
	public Download(String url, long size, String hash256, String fileName){
		this(url, size, hash256, fileName, true, null);
	}
	
	public Download(String url, long size, String hash256, String fileName, boolean enableSegment, DownloadListener<Download,Collection<Throwable>> listener) {
		super();
		this.url = url;
		this.size = size;
		this.hash256 = hash256;
		this.fileName = fileName;
		this.listener = listener;
		this.enableSegment = enableSegment;
		downloadMap.put(url, this);
	}
	
	public State getState() {
		return state;
	}

	public long getDownloadedBytes(){
		long c = 0L;
		for(Segment ch:segments)
			c+= ch.getDownloadedBytes();
		for(Segment ch:completedSegments)
			c+= ch.getDownloadedBytes();
		for(Segment ch:errSegments)
			c+= ch.getDownloadedBytes();
		
		return c;
	}
	
	int chunkCnt = 1;
	void start() throws DownloadException{
		if(state==State.CREATED||state==State.ERRORED||state==State.COMPLETED){
			DownloadManager.instance.submit(new Callable<Void>() {
				@Override
				public Void call() throws Exception {
					startDownload();
					return null;
				}
			});
		}else{
			throw new DownloadException("File is already in running state!");
		}
	}

	private final DownloadListener<Segment,Throwable> chunkListener = new DownloadListener<Segment,Throwable>() {
		ReentrantLock finishLock = new ReentrantLock(true);
		@Override
		public void onFinish(Segment c, Throwable e) {
//			System.out.println(" chunkListener.onFinish 1 e:"+e);
			finishLock.lock();
			try {
				finish(c, e);
			}finally{
				finishLock.unlock();
			}
		}
	};
	
	public float getPercentage(){
		return ((float) Math.round((float)(((double)getDownloadedBytes()/(double)size)*10000d)))/100f;
	}
	
	public String getFileName() {
		return fileName;
	}

	private void startDownload() {
		File downloadFile = new File(downloadFolder+this.fileName);
		if( state==State.ERRORED||state==State.COMPLETED ){
			state = State.RUNNING;
			segments.addAll(completedSegments);
			segments.addAll(errSegments);
			
			for(Segment c:segments)
				c.reset();

			completedSegments.clear();
			errSegments.clear();
			
			if(downloadFile.exists()){
				downloadFile.delete();
			}
			
			System.out.println("Reset "+fileName+" download state");
		}else if( state==State.CREATED ){
			state = State.RUNNING;
//			System.out.println("Started download "+fileName);
			if(enableSegment){
				chunkCnt = (int)(size/segmentSize+1L);
				for( int i=0;i<chunkCnt;i++ ){
					segments.add(new Segment(new File(downloadFolder+id+i), url, segmentSize, segmentSize*i, chunkListener));
				}
//				System.out.println(" Create "+chunkCnt+" Segments!");
			}else{
				segments.add(new Segment(downloadFile, url, 0L, 0L, chunkListener));	
			}
			if(downloadFile.exists()){
				downloadFile.delete();
			}
		}
		
		for(Segment c:segments)
			c.submit();
		
	}

	private void downloadCompleted() {
		File originalFile = new File(downloadFolder+this.fileName);
		if(enableSegment){
			mergeChunkFiles(originalFile);
		}
		try {
			System.out.println("Download "+fileName+" completed "+getDownloadedBytes()+" bytes downloaded! Hash match = "+hash256.equals(hash(originalFile)));
		} catch (NoSuchAlgorithmException | IOException e) {
			e.printStackTrace();
		}
		
		Collection<Throwable> errs = new ArrayList<>();
		if( errSegments.isEmpty() ){
			state = State.COMPLETED;
		}else{
			for( Segment c:errSegments )
				errs.add(c.getException());
			
			state = State.ERRORED;
		}
		
		if(this.listener!=null){
			this.listener.onFinish(this, errs);
		}
		downloadMap.remove(url);
	}

	private void mergeChunkFiles(File originalFile) {
		try(OutputStream output = new FileOutputStream(originalFile);){
			for( int i=0;i<chunkCnt;i++ ){
				File file = new File(downloadFolder+id+i);
				try(InputStream inputStream = new FileInputStream(file);){
					int n;
		        	byte[] buffer = new byte[1024];
		        	while ((n = inputStream.read(buffer)) > -1) {
						output.write(buffer, 0, n);
		        	}
				}
				file.delete();
			}
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	static String hash(File hFile) throws NoSuchAlgorithmException, IOException{
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		byte[] dataBytes = new byte[1024];
		int nread = 0; 
		try(
			FileInputStream fis = new FileInputStream(hFile);
		){
			while ((nread = fis.read(dataBytes)) != -1) {
				md.update(dataBytes, 0, nread);
			};
		}
        
        byte[] mdbytes = md.digest();
     
        //convert the byte to hex format method 1
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < mdbytes.length; i++) {
          sb.append(Integer.toString((mdbytes[i] & 0xff) + 0x100, 16).substring(1));
        }
//        System.out.println(" file "+hFile.getAbsolutePath()+" hash " + sb.toString());
        return sb.toString();
//        System.out.println("Hex format : " + sb.toString());
//        
//       //convert the byte to hex format method 2
//        StringBuffer hexString = new StringBuffer();
//    	for (int i=0;i<mdbytes.length;i++) {
//    	  hexString.append(Integer.toHexString(0xFF & mdbytes[i]));
//    	}
//
//    	System.out.println("Hex format : " + hexString.toString());
		
	}

	void finish(Segment chunk, Throwable exp) {
		if( exp == null ){
//			System.out.println(" DownloadFile finish complete exp :"+exp);
			segments.remove(chunk);
			completedSegments.add(chunk);
			if(segments.isEmpty()){
				downloadCompleted();
			}
			if(state==State.ERRORED){
				if( getRunningCnt() == 0 ){
					downloadCompleted();
				}
			}
		}else if( exp instanceof StopException ){
//			System.out.println(" DownloadFile finish StopException exp:"+exp);
			if(state==State.ERRORED){
				segments.remove(chunk);
				completedSegments.add(chunk);
				if(segments.isEmpty()){
					downloadCompleted();
				}else if( getRunningCnt() == 0 ){
					downloadCompleted();
				}
			}
		}else{
//			System.out.println(" DownloadFile finish NotStopException else :"+exp);
			if(state == State.ERRORED){
				segments.remove(chunk);
				errSegments.add(chunk);
			}else{
				state = State.ERRORED;
				segments.remove(chunk);
				errSegments.add(chunk);
				// some other exception stop all chunks
				for( Segment c:segments )
					c.pause();
			}
			
			if(segments.isEmpty()){
				downloadCompleted();
			}else if( getRunningCnt() == 0 ){
				downloadCompleted();
			}
		}
	}

	private int getRunningCnt() {
		int runningCnt = 0;
		for(Segment s:segments)
			if( s.getState() == State.RUNNING)
				runningCnt++;
		return runningCnt;
	}

	public void pause() throws DownloadException {
		if( state==State.RUNNING ){
			for( Segment c:segments )
				c.pause();
			
			state = State.PAUSED;
//			System.out.println("paused file "+fileName);
		}else if( state==State.PAUSED ){
			
		}else{
			throw new DownloadException("file in "+state);
		}
	}

	public void resume() throws DownloadException {
		if( state == State.COMPLETED || state == State.ERRORED )
			return;
		else if( state==State.PAUSED || state==State.CREATED ){
			state = State.RUNNING;
			for(Segment c:segments)
				c.resume();

//			System.out.println("resuming file "+fileName);		
		}else{
			throw new DownloadException("file in "+state);
		}
	}

}
class StopException extends Exception{

	/**
	 * 
	 */
	private static final long serialVersionUID = 6860554280264983221L;
	
}
class DownloadException extends Exception{

	/**
	 * 
	 */
	private static final long serialVersionUID = -1720277605329613639L;

	public DownloadException() {
		super();
	}

	public DownloadException(String arg0) {
		super(arg0);
	}
	
}

interface DownloadListener<T,E> {
	void onFinish(T c , E e);
}

class DownloadManager {

	static final DownloadManager instance = new DownloadManager(Download.DOWNLOAD_THREAD_CNT);
	
	private ExecutorService downloader = null;
	private final int size;
	private DownloadManager(int size) {
		this.size = size;
	}
	
	private volatile int runningCnt = 0;
	
	<T> Future<T> submit(final Callable<T> c) {
		if( downloader == null )
			downloader = Executors.newFixedThreadPool(this.size);

		runningCnt++;
		return downloader.submit(new Callable<T>() {
			@Override
			public T call() throws Exception {
				T call = null;
				try{
					call = c.call();
				}finally{
					runningCnt--;
//					System.err.println("DM finally runningCnt :: "+runningCnt);
					if( runningCnt == 0 ){
						ExecutorService tmp = downloader;
						downloader=null;
						tmp.shutdownNow();
					}
				}
				return call;
			}
		});
	}
	
}
class Segment implements Callable<Void> {

	private static final StopException se = new StopException();
	
	private final File file;
	private final String urlStr;
	
	private final DownloadListener<Segment,Throwable> listener;
	
	private long segmentCursor=0L;
	private final long segmentSize;
	private long cursor=0L;
	private final long range;
	
	private volatile State state = State.CREATED;
	
	private Future<Void> submit = null;
	private Throwable exception = null;

	public Segment(File file, String url, long segmentSize, long range, DownloadListener<Segment,Throwable> listener) {
		super();
		this.file = file;
		this.urlStr = url;
		this.segmentCursor = segmentSize;
		this.segmentSize = segmentSize;
		this.cursor = range;
		this.range = range;
		this.listener = listener;
	}

	long getDownloadedBytes(){
		return cursor - range;
	}
	
	public State getState() {
		return state;
	}

	public Throwable getException() {
		return exception;
	}

	public void run() {
		download();
	}
	
	void download(){
		state = State.RUNNING;
		try {
	        URL url = new URL(urlStr);
//	        String msg = urlStr;
	        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
	        urlConnection.setRequestProperty("X-VID", "C:\\tmp\\PANNAIYARUM PADMINIYUM - ONAKKAGA PORANTHENE HD SONGS.MP4");
	        urlConnection.setRequestProperty("X-RANGE", String.valueOf(cursor));
//	        msg+= " range:"+cursor;
	        
	        if(segmentCursor>0L){
	        	urlConnection.setRequestProperty("X-CHUNKSIZE", String.valueOf(segmentCursor));
//	        	msg+= " chunk:"+segmentCursor;
	        }
	        
	        urlConnection.connect();

//	        System.out.println("Started "+file.getAbsolutePath()+" download "+msg);
//	        System.out.println("Response Code: " + urlConnection.getResponseCode());
//	        System.out.println("Content-Length: " + urlConnection.getContentLengthLong());

	        try(InputStream inputStream = urlConnection.getInputStream();
	        	OutputStream output = new FileOutputStream(file,true);
	        	){
	        	int n;
	        	byte[] buffer = new byte[1024];
				while ((n = inputStream.read(buffer)) > -1) {
					output.write(buffer, 0, n);
					cursor += n;
					if(segmentCursor>0L)
						segmentCursor-=n;
					
					if( state == State.PAUSED ){
//						System.out.println("Paused "+file.getAbsolutePath()+" Read so for "+cursor+" bytes!");
						throw se;
					} 
				}
	        }

//	        System.out.println("Completed "+file.getAbsolutePath()+" size "+file.length()+" initRange "+range+" initChunk "+segmentSize );
	        state = State.COMPLETED;
	    }catch(Throwable e) {
	    	exception = e;
	    	if(e instanceof StopException){}else{
//	    		e.printStackTrace();
	    		System.err.println(e.toString());
	    	}
	    }
				
		if(this.listener!=null){
			this.listener.onFinish(this, exception);
		}
	}

	@Override
	public Void call() throws Exception {
		run();
		return null;
	}

	void reset() {
		if( file.exists() )
			file.delete();
		
		this.submit = null;
		this.exception = null;
		this.segmentCursor=segmentSize;
		this.cursor=range;
		state = State.CREATED;
	}

	void submit(){
		if( state!=State.RUNNING ){
			submit = DownloadManager.instance.submit(this);
//			System.out.println("Submitted "+file.getAbsolutePath()+" file to exe!");
		}
	}

	void pause(){
		if(	state == State.RUNNING)
			state = State.PAUSED;
		else if(	state == State.CREATED){
			state = State.PAUSED;
			
			if(submit!=null)
				submit.cancel(true);
			
			if(this.listener!=null){
				this.listener.onFinish(this, se);
			}
		}
	}
	
	void resume(){
		if( state == State.PAUSED ){
			if( file.length() != getDownloadedBytes() ){
				throw new IllegalStateException("Download segment file("+file.getAbsolutePath()+") corrupted!"); 
			}
			exception = null;
			submit();
		}
	}
}