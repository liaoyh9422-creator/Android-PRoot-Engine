package bin.mt.file.content;

import android.app.Activity;
import android.os.Bundle;

/**
 * Activity invoked by MT Manager to wake up the application process
 * so that its DocumentsProvider (MTDataFilesProvider) can be accessed.
 */
public class MTDataFilesWakeUpActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
