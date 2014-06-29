package org.appspot.apprtc;

public interface ChatStateListener {

	public void onVolumeLevelChanged(int level);
	public void onVolumeLevelDetected(int level);
	public void onChatStart();
	public void onChatTimeUpdate();
}
