/*
 * libjingle
 * Copyright 2013, Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.appspot.apprtc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRenderer.I420Frame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main Activity of the AppRTCDemo Android app demonstrating interoperability
 * between the Android/Java implementation of PeerConnection and the
 * apprtc.appspot.com demo webapp.
 */
public class AppRTCDemoActivity extends Activity
    implements AppRTCClient.IceServersObserver {
  private static final String TAG = "AppRTCDemoActivity";
  private PeerConnectionFactory factory;
  private VideoSource videoSource;
  private boolean videoSourceStopped;
  private PeerConnection pc;
  private final PCObserver pcObserver = new PCObserver();
  private final SDPObserver sdpObserver = new SDPObserver();
  private final SockJSChannelClient.MessageHandler gaeHandler = new GAEHandler();
  private AppRTCClient appRtcClient = new AppRTCClient(this, gaeHandler, this);
  private VideoStreamsView vsv;
  private Toast logToast;
  private LinkedList<IceCandidate> queuedRemoteCandidates =new LinkedList<IceCandidate>();
  private final Boolean[] quit = new Boolean[] { false };
  private MediaConstraints sdpMediaConstraints;
  private VolumeSeekBar m_volumeSeekBar = null;
  private AudioManager m_audioManager;
  private int m_totalChatSeconds = 3600;
  private int m_chatSecondsElapsed;
  private Timer m_timeCounterTimer = new Timer();
  private Handler m_uiThreadHandler;
  private TextView m_timeHintView;
  private ChatStateListener m_chatStateListener;
  private VideoCapturer m_currentCapturer;
  private String m_currentCapturerFacing="front";
  private MediaStream m_localMediaStream;
  private VideoTrack m_currentVideoTrack;
  private VideoRenderer m_localVideoRenderer;
  private AudioTrack  m_audioTrack;
  private Boolean m_offerCreated = false;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Thread.setDefaultUncaughtExceptionHandler(
        new UnhandledExceptionHandler(this));

    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    Point displaySize = new Point();
    getWindowManager().getDefaultDisplay().getSize(displaySize);
    
    setContentView(R.layout.main);
    //vsv = new VideoStreamsView(this, displaySize);
    vsv = (VideoStreamsView)findViewById(R.id.graphics_glsurfaceview1);
    //ViewGroup vg = (ViewGroup)findViewById(R.id.view_root);
    //vg.addView(vsv);
    
    m_audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
	m_uiThreadHandler = new Handler();
	
    initViews();
    //setContentView(vsv);
    abortUnless(PeerConnectionFactory.initializeAndroidGlobals(this),
        "Failed to initializeAndroidGlobals");

    AudioManager audioManager =
        ((AudioManager) getSystemService(AUDIO_SERVICE));
    // TODO(fischman): figure out how to do this Right(tm) and remove the
    // suppression.
    @SuppressWarnings("deprecation")
    boolean isWiredHeadsetOn = audioManager.isWiredHeadsetOn();
    audioManager.setMode(isWiredHeadsetOn ?
        AudioManager.MODE_IN_CALL : AudioManager.MODE_IN_COMMUNICATION);
    audioManager.setSpeakerphoneOn(!isWiredHeadsetOn);

    sdpMediaConstraints = new MediaConstraints();
    sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
        "OfferToReceiveAudio", "true"));
    sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
        "OfferToReceiveVideo", "true"));

    final Intent intent = getIntent();
    if ("android.intent.action.VIEW".equals(intent.getAction())) {
      connectToRoom(intent.getData().toString());
      return;
    }
    showGetRoomUI();
    //connectToRoom(roomInput.getText().toString());
  }
  
  private void initViews(){
	    Button btn = (Button)findViewById(R.id.close_btn);
	    btn.setOnClickListener(new View.OnClickListener() {
			
			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				AppRTCDemoActivity.this.finish();
			}
		});
	    
		m_volumeSeekBar = (VolumeSeekBar)findViewById(R.id.volume_seekbar);
		int max_volume =m_audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		int current_volume = m_audioManager.getStreamVolume(AudioManager.STREAM_MUSIC); 
		m_volumeSeekBar.setAudioManager(m_audioManager);
		m_volumeSeekBar.setMax(max_volume);
		m_volumeSeekBar.setProgress(current_volume);	
		
		m_timeHintView = (TextView)findViewById(R.id.time_hint_view);
		Button switch_camera_btn = (Button)findViewById(R.id.camera_btn);
		switch_camera_btn.setOnClickListener(new View.OnClickListener() {
			
			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				switchCamera();
			}
		});
	
		m_chatStateListener = new ChatStateListener() {
			
			@Override
			public void onVolumeLevelChanged(int level) {
				// TODO Auto-generated method stub
				updateVolumeLevel(level);
				Log.d(TAG, "Volume:"+String.valueOf(level));
			}

			@Override
			public void onVolumeLevelDetected(int level) {
				// TODO Auto-generated method stub
				updateVolumeLevel(level);
			}

			@Override
			public void onChatStart() {
				// TODO Auto-generated method stub
				m_timeCounterTimer.scheduleAtFixedRate(new TimeCounterTask(), 1000, 1000);
			}

			@Override
			public void onChatTimeUpdate() {
				// TODO Auto-generated method stub
				runOnUiThread(new Runnable(){

					@Override
					public void run() {
						// TODO Auto-generated method stub
						m_timeHintView.setText(String.valueOf(m_chatSecondsElapsed));
					}
				});
			}
		};
		m_volumeSeekBar.setVolumeStateListener(m_chatStateListener);
		m_chatStateListener.onVolumeLevelDetected(current_volume);
		
		View timer_stop_container = findViewById(R.id.timer_stop_container);
		timer_stop_container.setOnTouchListener(new View.OnTouchListener() {
			
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				// TODO Auto-generated method stub
				switch (event.getAction()) {
					case MotionEvent.ACTION_DOWN: {
						m_volumeSeekBar.setVisibility(View.INVISIBLE);
						break;
					}
				}
				return false;
			}
		});
		
		Button volume_btn = (Button)findViewById(R.id.volume_btn);
		volume_btn.setOnClickListener(new View.OnClickListener() {
			
			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				m_volumeSeekBar.setVisibility(View.VISIBLE);
			}
		});
		
		vsv.setOnTouchListener(new View.OnTouchListener() {
			
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				// TODO Auto-generated method stub
				switch (event.getAction()) {
					case MotionEvent.ACTION_DOWN: {
						m_volumeSeekBar.setVisibility(View.INVISIBLE);
						break;
					}
				}
				return false;
			}
		});
		
		if(getCamerasCount() <= 1){
			switch_camera_btn.setVisibility(View.INVISIBLE);
		}
  }
  
  private int getCamerasCount(){
	  int numCameras= Camera.getNumberOfCameras();
	  return numCameras;
  }
  
  private boolean hasFrontCamera(){
	  int numCameras= Camera.getNumberOfCameras();
		for(int i=0;i<numCameras;i++){
		    Camera.CameraInfo info = new CameraInfo();
		    Camera.getCameraInfo(i, info);
		    if(CameraInfo.CAMERA_FACING_FRONT == info.facing){
		        return true;
		    }
		}
	  return false;
  }
  
  private boolean hasBackCamera(){
	  int numCameras= Camera.getNumberOfCameras();
		for(int i=0;i<numCameras;i++){
		    Camera.CameraInfo info = new CameraInfo();
		    Camera.getCameraInfo(i, info);
		    if(CameraInfo.CAMERA_FACING_BACK == info.facing){
		        return true;
		    }
		}
	  return false;
  }
 
  private void updateVolumeLevel(int level){
	  
  }

  private void showGetRoomUI() {
    final EditText roomInput = new EditText(this);
    //roomInput.setText("https://apprtc.appspot.com/?r=");
    roomInput.setText("http://115.28.172.76:2013/?chanel=52001416");
    //roomInput.setText("http://192.168.0.102:8080/?chanel=52001416");
    roomInput.setSelection(roomInput.getText().length());
    DialogInterface.OnClickListener listener =
        new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            abortUnless(which == DialogInterface.BUTTON_POSITIVE, "lolwat?");
            dialog.dismiss();
            connectToRoom(roomInput.getText().toString());
          }
        };
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder
        .setMessage("Enter room URL").setView(roomInput)
        .setPositiveButton("Go!", listener).show();
  }

  private void connectToRoom(String roomUrl) {
    logAndToast("Connecting to room...");
    try{
    	appRtcClient.connectToRoom(roomUrl);
    }catch(Exception e){
    	logAndToast("room is full");
    	finish();
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    vsv.onPause();
    if (videoSource != null) {
      videoSource.stop();
      videoSourceStopped = true;
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    vsv.onResume();
    if (videoSource != null && videoSourceStopped) {
      videoSource.restart();
    }
  }


  // Just for fun (and to regression-test bug 2302) make sure that DataChannels
  // can be created, queried, and disposed.
  private static void createDataChannelToRegressionTestBug2302(
      PeerConnection pc) {
    DataChannel dc = pc.createDataChannel("dcLabel", new DataChannel.Init());
    abortUnless("dcLabel".equals(dc.label()), "WTF?");
    dc.close();
    dc.dispose();
  }

  @Override
  public void onIceServers(List<PeerConnection.IceServer> iceServers) {
    factory = new PeerConnectionFactory();

    MediaConstraints pcConstraints = appRtcClient.pcConstraints();
    pcConstraints.optional.add(
        new MediaConstraints.KeyValuePair("RtpDataChannels", "true"));
    MediaConstraints.KeyValuePair dtls = new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true");
    pcConstraints.optional.add(dtls);
    pc = factory.createPeerConnection(iceServers, pcConstraints, pcObserver);

    createDataChannelToRegressionTestBug2302(pc);  // See method comment.

    // Uncomment to get ALL WebRTC tracing and SENSITIVE libjingle logging.
    // NOTE: this _must_ happen while |factory| is alive!
    // Logging.enableTracing(
    //     "logcat:",
    //     EnumSet.of(Logging.TraceLevel.TRACE_ALL),
    //     Logging.Severity.LS_SENSITIVE);

    {
      final PeerConnection finalPC = pc;
      final Runnable repeatedStatsLogger = new Runnable() {
          public void run() {
            synchronized (quit[0]) {
              if (quit[0]) {
                return;
              }
              final Runnable runnableThis = this;
              boolean success = finalPC.getStats(new StatsObserver() {
                  public void onComplete(StatsReport[] reports) {
                    for (StatsReport report : reports) {
                      Log.d(TAG, "Stats: " + report.toString());
                    }
                    vsv.postDelayed(runnableThis, 10000);
                  }
                }, null);
              if (!success) {
                throw new RuntimeException("getStats() return false!");
              }
            }
          }
        };
      vsv.postDelayed(repeatedStatsLogger, 10000);
    }

    {
      m_localVideoRenderer = new VideoRenderer(new VideoCallbacks(
             vsv, VideoStreamsView.Endpoint.LOCAL));
      logAndToast("Creating local video source...");
      m_localMediaStream = factory.createLocalMediaStream("ARDAMS");
      if (appRtcClient.videoConstraints() != null) {
    	if(hasFrontCamera()){
            VideoCapturer captuer= getVideoCapturer("front");
        	videoSource = factory.createVideoSource(captuer, appRtcClient.videoConstraints());
            VideoTrack video_track = factory.createVideoTrack("ARDAMSv0", videoSource);
            video_track.addRenderer(m_localVideoRenderer);
            m_localMediaStream.addTrack(video_track);
            m_currentCapturerFacing = "front";
    	}else if(hasBackCamera()){
            VideoCapturer captuer= getVideoCapturer("back");
        	videoSource = factory.createVideoSource(captuer, appRtcClient.videoConstraints());
            VideoTrack video_track = factory.createVideoTrack("ARDAMSv0", videoSource);
            video_track.addRenderer(m_localVideoRenderer);
            m_localMediaStream.addTrack(video_track);
            m_currentCapturerFacing = "back";
    	}
      }
      if (appRtcClient.audioConstraints() != null) {
    	  m_audioTrack = factory.createAudioTrack(
    	            "ARDAMSa0",
    	            factory.createAudioSource(appRtcClient.audioConstraints()));
    	  m_localMediaStream.addTrack(m_audioTrack);
      }
      pc.addStream(m_localMediaStream, new MediaConstraints());
    }
    logAndToast("Waiting for ICE candidates...");
  }

  // Cycle through likely device names for the camera and return the first
  // capturer that works, or crash if none do.
//  private VideoCapturer getVideoCapturer() {
//    String[] cameraFacing = {  "back","front"};
//    int[] cameraIndex = { 0, 1 };
//    int[] cameraOrientation = {0,90,180,270};
//    for (String facing : cameraFacing) {
//      for (int index : cameraIndex) {
//        for (int orientation : cameraOrientation) {
//          String name = "Camera " + index + ", Facing " + facing +
//              ", Orientation " + orientation;
//          VideoCapturer capturer = VideoCapturer.create(name);
//          if (capturer != null) {
//            logAndToast("Using camera: " + name);
//            return capturer;
//          }
//        }
//      }
//    }
//    throw new RuntimeException("Failed to open capturer");
//  }
  
  private VideoCapturer getVideoCapturer(String facing){
	    int[] cameraIndex = { 0, 1 };
	    int[] cameraOrientation = {270,180,90,0};
	    for (int index : cameraIndex) {
	        for (int orientation : cameraOrientation) {
	          String name = "Camera " + index + ", Facing " + facing +
	              ", Orientation " + orientation;
	          VideoCapturer capturer = VideoCapturer.create(name);
	          if (capturer != null) {
	            logAndToast("Using camera: " + name);
	            return capturer;
	          }
	        }
	      }
	    throw new RuntimeException("Failed to open capturer");
  }
  
  public void switchCamera(){
	  videoSource.stop();
	  pc.removeStream(m_localMediaStream);
	  m_localMediaStream = factory.createLocalMediaStream("ARDAMS");
	  //m_localMediaStream.addTrack(m_audioTrack);
	  if(m_currentCapturerFacing == "front"){
	        m_currentCapturerFacing = "back";
	     	VideoCapturer capture = getVideoCapturer("back");
	    	videoSource = factory.createVideoSource(capture, appRtcClient.videoConstraints());
	        VideoTrack track = factory.createVideoTrack("ARDAMSv0", videoSource);
	        track.addRenderer(m_localVideoRenderer);
	        m_localMediaStream.addTrack(track);
	        pc.addStream(m_localMediaStream, new MediaConstraints());
	  }else if(m_currentCapturerFacing == "back"){
	        m_currentCapturerFacing = "front";
	        VideoCapturer capture = getVideoCapturer("front");
	    	videoSource = factory.createVideoSource(capture, appRtcClient.videoConstraints());
	        VideoTrack track = factory.createVideoTrack("ARDAMSv0", videoSource);
	        track.addRenderer(m_localVideoRenderer);
	        m_localMediaStream.addTrack(track);
	        pc.addStream(m_localMediaStream, new MediaConstraints());
	  }
  }

  @Override
  protected void onDestroy() {
    disconnectAndExit();
    super.onDestroy();
  }

  // Poor-man's assert(): die with |msg| unless |condition| is true.
  private static void abortUnless(boolean condition, String msg) {
    if (!condition) {
      throw new RuntimeException(msg);
    }
  }

  // Log |msg| and Toast about it.
  private void logAndToast(String msg) {
    Log.d(TAG, msg);
    if (logToast != null) {
      logToast.cancel();
    }
    logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
    logToast.show();
  }

  // Send |json| to the underlying AppEngine Channel.
  private void sendMessage(JSONObject json) {
    appRtcClient.sendMessage(json.toString());
  }

  // Put a |key|->|value| mapping in |json|.
  private static void jsonPut(JSONObject json, String key, Object value) {
    try {
      json.put(key, value);
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
  }

  // Mangle SDP to prefer ISAC/16000 over any other audio codec.
  private String preferISAC(String sdpDescription) {
    String[] lines = sdpDescription.split("\n");
    int mLineIndex = -1;
    String isac16kRtpMap = null;
    Pattern isac16kPattern =
        Pattern.compile("^a=rtpmap:(\\d+) ISAC/16000[\r]?$");
    for (int i = 0;
         (i < lines.length) && (mLineIndex == -1 || isac16kRtpMap == null);
         ++i) {
      if (lines[i].startsWith("m=audio ")) {
        mLineIndex = i;
        continue;
      }
      Matcher isac16kMatcher = isac16kPattern.matcher(lines[i]);
      if (isac16kMatcher.matches()) {
        isac16kRtpMap = isac16kMatcher.group(1);
        continue;
      }
    }
    if (mLineIndex == -1) {
      Log.d(TAG, "No m=audio line, so can't prefer iSAC");
      return sdpDescription;
    }
    if (isac16kRtpMap == null) {
      Log.d(TAG, "No ISAC/16000 line, so can't prefer iSAC");
      return sdpDescription;
    }
    String[] origMLineParts = lines[mLineIndex].split(" ");
    StringBuilder newMLine = new StringBuilder();
    int origPartIndex = 0;
    // Format is: m=<media> <port> <proto> <fmt> ...
    newMLine.append(origMLineParts[origPartIndex++]).append(" ");
    newMLine.append(origMLineParts[origPartIndex++]).append(" ");
    newMLine.append(origMLineParts[origPartIndex++]).append(" ");
    newMLine.append(isac16kRtpMap).append(" ");
    for (; origPartIndex < origMLineParts.length; ++origPartIndex) {
      if (!origMLineParts[origPartIndex].equals(isac16kRtpMap)) {
        newMLine.append(origMLineParts[origPartIndex]).append(" ");
      }
    }
    lines[mLineIndex] = newMLine.toString();
    StringBuilder newSdpDescription = new StringBuilder();
    for (String line : lines) {
      newSdpDescription.append(line).append("\n");
    }
    return newSdpDescription.toString();
  }

  // Implementation detail: observe ICE & stream changes and react accordingly.
  private class PCObserver implements PeerConnection.Observer {
    @Override public void onIceCandidate(final IceCandidate candidate){
      runOnUiThread(new Runnable() {
          public void run() {
            JSONObject json = new JSONObject();
            jsonPut(json, "type", "candidate");
            jsonPut(json, "label", candidate.sdpMLineIndex);
            jsonPut(json, "id", candidate.sdpMid);
            jsonPut(json, "candidate", candidate.sdp);
            sendMessage(json);
          }
        });
    }

    @Override public void onError(){
      runOnUiThread(new Runnable() {
          public void run() {
            throw new RuntimeException("PeerConnection error!");
          }
        });
    }

    @Override public void onSignalingChange(
        PeerConnection.SignalingState newState) {
    }

    @Override public void onIceConnectionChange(
        PeerConnection.IceConnectionState newState) {
    }

    @Override public void onIceGatheringChange(
        PeerConnection.IceGatheringState newState) {
    }

    @Override public void onAddStream(final MediaStream stream){
      runOnUiThread(new Runnable() {
          public void run() {
            abortUnless(stream.audioTracks.size() <= 1 &&
                stream.videoTracks.size() <= 1,
                "Weird-looking stream: " + stream);
            if (stream.videoTracks.size() == 1) {
              stream.videoTracks.get(0).addRenderer(new VideoRenderer(
                  new VideoCallbacks(vsv, VideoStreamsView.Endpoint.REMOTE)));
              	  m_chatStateListener.onChatStart();
            }
          }
        });
    }

    @Override public void onRemoveStream(final MediaStream stream){
      runOnUiThread(new Runnable() {
          public void run() {
            stream.videoTracks.get(0).dispose();
          }
        });
    }

    @Override public void onDataChannel(final DataChannel dc) {
      runOnUiThread(new Runnable() {
          public void run() {
            throw new RuntimeException(
                "AppRTC doesn't use data channels, but got: " + dc.label() +
                " anyway!");
          }
        });
    }

    @Override public void onRenegotiationNeeded() {
      // No need to do anything; AppRTC follows a pre-agreed-upon
      // signaling/negotiation protocol.
    }
  }

  // Implementation detail: handle offer creation/signaling and answer setting,
  // as well as adding remote ICE candidates once the answer SDP is set.
  private class SDPObserver implements SdpObserver {
    @Override public void onCreateSuccess(final SessionDescription origSdp) {
      runOnUiThread(new Runnable() {
          public void run() {
            SessionDescription sdp = new SessionDescription(
                origSdp.type, preferISAC(origSdp.description));
            pc.setLocalDescription(sdpObserver, sdp);
          }
        });
    }

    // Helper for sending local SDP (offer or answer, depending on role) to the
    // other participant.
    private void sendLocalDescription(PeerConnection pc) {
      SessionDescription sdp = pc.getLocalDescription();
      logAndToast("Sending " + sdp.type);
      JSONObject json = new JSONObject();
      jsonPut(json, "type", sdp.type.canonicalForm());
      jsonPut(json, "sdp", sdp.description);
      sendMessage(json);
    }

    @Override public void onSetSuccess() {
      runOnUiThread(new Runnable() {
          public void run() {
            if (appRtcClient.isInitiator()) {
              if (pc.getRemoteDescription() != null) {
                // We've set our local offer and received & set the remote
                // answer, so drain candidates.
                drainRemoteCandidates();
              } else {
                // We've just set our local description so time to send it.
                sendLocalDescription(pc);
              }
            } else {
              if (pc.getLocalDescription() == null) {
                // We just set the remote offer, time to create our answer.
                logAndToast("Creating answer");
                pc.createAnswer(SDPObserver.this, sdpMediaConstraints);
              } else {
                // Answer now set as local description; send it and drain
                // candidates.
                sendLocalDescription(pc);
                drainRemoteCandidates();
              }
            }
          }
        });
    }

    @Override public void onCreateFailure(final String error) {
      runOnUiThread(new Runnable() {
          public void run() {
            throw new RuntimeException("createSDP error: " + error);
          }
        });
    }

    @Override public void onSetFailure(final String error) {
      runOnUiThread(new Runnable() {
          public void run() {
            throw new RuntimeException("setSDP error: " + error);
          }
        });
    }

    private void drainRemoteCandidates() {
      for (IceCandidate candidate : queuedRemoteCandidates) {
        pc.addIceCandidate(candidate);
      }
      queuedRemoteCandidates = null;
    }
  }

  // Implementation detail: handler for receiving GAE messages and dispatching
  // them appropriately.
  private class GAEHandler implements SockJSChannelClient.MessageHandler {
    @JavascriptInterface public void onOpen() {
      if (!appRtcClient.isInitiator()) {
        return;
      }
      //pc.createOffer(sdpObserver, sdpMediaConstraints);
	  if(m_offerCreated == false){
	    logAndToast("Creating offer...");
		pc.createOffer(sdpObserver, sdpMediaConstraints);
		m_offerCreated = true;
	  }
    }

    @JavascriptInterface public void onMessage(String data) {
      try {
        JSONObject json = new JSONObject(data);
        String type = (String) json.get("type");
        if (type.equals("candidate")) {
          IceCandidate candidate = new IceCandidate(
              (String) json.get("id"),
              json.getInt("label"),
              (String) json.get("candidate"));
          if (queuedRemoteCandidates != null) {
            queuedRemoteCandidates.add(candidate);
          } else {
            pc.addIceCandidate(candidate);
          }
        } else if (type.equals("answer") || type.equals("offer")) {
          SessionDescription sdp = new SessionDescription(
              SessionDescription.Type.fromCanonicalForm(type),
              preferISAC((String) json.get("sdp")));
          pc.setRemoteDescription(sdpObserver, sdp);
        } else if (type.equals("bye")) {
          logAndToast("Remote end hung up; dropping PeerConnection");
          disconnectAndExit();
        } else {
          throw new RuntimeException("Unexpected message: " + data);
        }
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }
    }

    @JavascriptInterface public void onClose() {
      //disconnectAndExit();
    }

    @JavascriptInterface public void onError(int code, String description) {
      disconnectAndExit();
    }
  }

  // Disconnect from remote resources, dispose of local resources, and exit.
  private void disconnectAndExit() {
    synchronized (quit[0]) {
      if (quit[0]) {
        return;
      }
      quit[0] = true;
      try{
          if (pc != null) {
              pc.dispose();
              pc = null;
            }
      }catch(Exception e){
    	  e.printStackTrace();
      }
      if (appRtcClient != null) {
        appRtcClient.sendMessage("{\"type\": \"bye\"}");
        appRtcClient.disconnect();
        appRtcClient = null;
      }
      if (videoSource != null) {
        videoSource.dispose();
        videoSource = null;
      }
      if (factory != null) {
        factory.dispose();
        factory = null;
      }
      finish();
    }
  }

  // Implementation detail: bridge the VideoRenderer.Callbacks interface to the
  // VideoStreamsView implementation.
  private class VideoCallbacks implements VideoRenderer.Callbacks {
    private final VideoStreamsView view;
    private final VideoStreamsView.Endpoint stream;

    public VideoCallbacks(
        VideoStreamsView view, VideoStreamsView.Endpoint stream) {
      this.view = view;
      this.stream = stream;
    }

    @Override
    public void setSize(final int width, final int height) {
      view.queueEvent(new Runnable() {
          public void run() {
            view.setSize(stream, width, height);
          }
        });
    }

    @Override
    public void renderFrame(I420Frame frame) {
      view.queueFrame(stream, frame);
    }
  }
  
	class TimeCounterTask extends TimerTask
	{
	  @Override
      public void run() {  
		  m_chatSecondsElapsed ++;
		  m_chatStateListener.onChatTimeUpdate();
		}
	}
}
