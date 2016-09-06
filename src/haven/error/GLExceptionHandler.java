package haven.error;

import com.jogamp.opengl.GLAnimatorControl;
import com.jogamp.opengl.GLAutoDrawable;

public class GLExceptionHandler implements GLAnimatorControl.UncaughtExceptionHandler {

    @Override
    public void uncaughtException(GLAnimatorControl glAnimatorControl, GLAutoDrawable glAutoDrawable, Throwable throwable) {
        new ErrorGui(null).goterror(throwable);
    }
}
