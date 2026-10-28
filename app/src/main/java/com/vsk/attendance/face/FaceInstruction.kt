package com.vsk.attendance.face

/**
 * Guidance shown to the employee. Field staff read one short line in their own language, so every
 * instruction carries both and the screen never has to translate a dynamically built sentence.
 */
internal data class FaceInstruction(val english: String, val hindi: String) {
    override fun toString(): String = english
}

/**
 * The whole vocabulary a face check can speak. Keeping it in one place is what limits the employee
 * to a handful of lines instead of a paragraph, and makes a missing translation obvious.
 */
internal object FaceCopy {
    val IDLE = FaceInstruction("Getting the camera ready", "कैमरा तैयार हो रहा है")
    val READY_TO_START = FaceInstruction(
        "Hold the phone at eye level in even light",
        "फ़ोन आँखों की ऊँचाई पर रखिए, अच्छी रोशनी में"
    )
    val LOOK = FaceInstruction("Look straight at the camera", "कैमरे की ओर सीधे देखिए")
    val BLINK_NOW = FaceInstruction("Now blink your eyes", "अब पलकें झपकाइए")
    val KEEP_LOOKING = FaceInstruction("Keep looking at the camera", "ऐसे ही कैमरे को देखते रहिए")
    val COMPLETE = FaceInstruction("Face check complete", "चेहरा जाँच पूरी हुई")
    val TIMED_OUT = FaceInstruction(
        "Face check timed out. Start again",
        "समय हो गया। फिर शुरू करें"
    )
    val SLOW = FaceInstruction(
        "Move to better light and hold very still",
        "अच्छी रोशनी में बिल्कुल स्थिर रहें"
    )
    val UNREADABLE = FaceInstruction(
        "Face could not be read. Look at the camera",
        "चेहरा पढ़ा नहीं जा सका। कैमरे को देखिए"
    )
    val NO_MATCH = FaceInstruction(
        "Not your registered face. Look at the camera",
        "यह दर्ज चेहरा नहीं है। कैमरे को देखिए"
    )
    val ONE_PERSON = FaceInstruction("Keep the same person in view", "एक ही व्यक्ति को फ़्रेम में रखिए")
    val FACE_LOST = FaceInstruction("Put your face in the camera frame", "चेहरा कैमरे के फ़्रेम में रखिए")
    val MOVE_CLOSER = FaceInstruction("Move a little closer", "कैमरे के थोड़ा पास आइए")
    val MOVE_BACK = FaceInstruction("Move back a little", "थोड़ा पीछे हटिए")
    val FACE_IN_FRAME = FaceInstruction(
        "Keep your whole face inside the oval",
        "पूरा चेहरा रेखा के अंदर रखिए"
    )
    val UPRIGHT = FaceInstruction("Hold your head straight", "सिर सीधा रखिए")
    val TOO_DARK = FaceInstruction("Face a light source", "रोशनी की ओर देखिए")
    val TOO_BRIGHT = FaceInstruction("Step out of direct sunlight", "तेज़ धूप से थोड़ा दूर रहिए")
    val FLAT_LIGHT = FaceInstruction(
        "Use even light and clean the front camera",
        "समान रोशनी में रहिए, फ़्रंट कैमरा साफ़ कीजिए"
    )
    val TOO_BLURRY = FaceInstruction(
        "Hold still and clean the camera lens",
        "स्थिर रहिए, कैमरे का लेंस साफ़ कीजिए"
    )
    val ENGINE_UNAVAILABLE = FaceInstruction(
        "Face check is unavailable on this phone. Contact your administrator",
        "इस फ़ोन पर चेहरा जाँच उपलब्ध नहीं है। प्रशासक से संपर्क करें"
    )
    val CAMERA_FAILED = FaceInstruction(
        "Front camera could not start. Close this screen and retry",
        "फ़्रंट कैमरा शुरू नहीं हुआ। स्क्रीन बंद कर फिर करें"
    )
    val MODEL_FAILED = FaceInstruction(
        "Face models could not load. Reinstall the official app",
        "चेहरा मॉडल लोड नहीं हुए। आधिकारिक ऐप फिर डालें"
    )
    val PROCESSING_FAILED = FaceInstruction(
        "Face check failed. Close this screen and try again",
        "चेहरा जाँच विफल रही। स्क्रीन बंद कर फिर करें"
    )
}
