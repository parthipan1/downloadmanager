package org.arivu.download;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;

import org.arivu.download.Download.State;

public class Main {
	  
	public static String file = "C:\\tmp\\PANNAIYARUM PADMINIYUM - ONAKKAGA PORANTHENE HD SONGS.MP4";
	
	public static void main(String[] args) throws InterruptedException, DownloadException, NoSuchAlgorithmException, IOException{
		final CountDownLatch countDownLatch = new CountDownLatch(1);
		long size2 = 50621480L;//37030599L;
		String hash256 = "39077572110b0167900e7cefb208a5963df4b3d241971f41b450d152ac8d27c2";//"b5594710b5f769e2d323903b0eccfb6fdcc39bed305215f3b409cc114980fa51";
		String fileName = "PANNAIYARUM PADMINIYUM - ONAKKAGA PORANTHENE HD SONGS.MP4";//"3fed970b-a220-404e-bb00-d847e88638f7.apk";
		
		DownloadListener<Download,Collection<Throwable>> listener = new DownloadListener<Download,Collection<Throwable>>() {
			@Override
			public void onFinish(Download c, Collection<Throwable> e) {
				if( c.getState()==State.ERRORED ){
					System.err.println("Download "+c.getFileName()+" has failed with errors! downloaded "+c.getPercentage()+"%");
					for( Throwable t:e ){
						System.err.println(t.toString());
					}
				}else{
//					System.out.println("Download "+c.getFileName()+" completed!");
				}
				countDownLatch.countDown();
			}
		};
		
		Download f = new Download("test", size2, hash256, fileName, true, listener);
		f.start();
		Thread.sleep(10);
		f.pause();
//		System.out.println(" File paused "+f.getDownloadedBytes()+" downloaded so far");
		Thread.sleep(10000);
		f.resume();
		countDownLatch.await();

//		System.out.println( " hash::"+DownloadFile.hash(new File("C:\\Users\\AadityaD\\Desktop\\snw\\curl\\Adobe Reader.jpeg")) );
//		System.out.println( " hash::"+DownloadFile.hash(new File("C:\\Users\\AadityaD\\Desktop\\snw\\curl\\com.adobe.reader-11.5.0.1.apk")) );
	}

}
