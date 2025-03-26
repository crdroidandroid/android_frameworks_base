package com.android.systemui.statusbar.phone

class FaceUnlockController private constructor() {

    private var faceUnlockImageView: FaceUnlockImageView? = null

    fun setFaceUnlockView(view: FaceUnlockImageView) {
        faceUnlockImageView = view
    }

    fun setBouncerState(state: FaceUnlockImageView.State) {
        faceUnlockImageView?.postDelayed({
            faceUnlockImageView?.setState(state)
        }, 100)
    }
    
    companion object {
        @Volatile
        private var instance: FaceUnlockController? = null

        fun getInstance(): FaceUnlockController {
            return instance ?: synchronized(this) {
                instance ?: FaceUnlockController().also { instance = it }
            }
        }
    }
}
