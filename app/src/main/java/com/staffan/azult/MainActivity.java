package com.staffan.azult;

import static android.Manifest.permission.INTERNET;
import static android.Manifest.permission.RECORD_AUDIO;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Guideline;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.microsoft.cognitiveservices.speech.CancellationErrorCode;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends AppCompatActivity {

    private String speechSubscriptionKey = "";
    private String speechRegion = "";

    private TextView transcriptionTextView;
    private Button langButton;

    private final String primaryLanguage = "sv-SE";
    private final String secondaryLanguage = "en-US";

    private boolean continuousListeningStarted = false;
    private SpeechRecognizer reco = null;
    private ArrayList<String> content = new ArrayList<>();

    private boolean primaryLanguageSelected = true;

    private boolean showOwnText=false;
    private float ownTextScreenPercentage = 0.33f;

    private SpeechConfig speechConfig = null;

    private String currentSessionID = "";  // keeps track of current text stream (to handle language changes better)

    @Override
    protected void onStop() {
        super.onStop();

        if (reco != null)
            reco.stopContinuousRecognitionAsync();

    }

    @Override
    protected void onRestart() {
        super.onRestart();

        Log.i("AzuLT","onRestart");
        // User returned to the app after doing something else.  No need to do anything, onResume is also called
    }


    @Override
    protected void onResume() {
        super.onResume();

        Log.i("AzuLT","onResume");
        // Usually we're resuming because we've either changed a setting or temporarily used another app
        // So here we set up the speech config (a check is done in setupSpeechConfig whether the settings was changed or not
        // and if not, then no action is taken)
        setupSpeechConfig();

        if(reco != null)
            reco.startContinuousRecognitionAsync();

        // adjust font sizes in case they got changed

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        int fontsize = sharedPreferences.getInt("fontsize", 42);
        transcriptionTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontsize);
        EditText ownTextView = (EditText) findViewById(R.id.owntext);
        ownTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontsize);

    }

    public void onChangeLanguageClick(View view) {
        primaryLanguageSelected = !primaryLanguageSelected;

        if(reco != null && continuousListeningStarted) {
            Log.i("stt", "changing language - stopping old stream");

            reco.stopContinuousRecognitionAsync();
            continuousListeningStarted = false;
        }

        if(primaryLanguageSelected) {
            setupRecognizer("sv-SE");
            langButton.setText("Swedish");
        }
        else {
            setupRecognizer("en-US");
            langButton.setText("English");
        }
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getSupportActionBar().hide();

        transcriptionTextView = findViewById(R.id.transcribedTextView);
        transcriptionTextView.setMovementMethod(new ScrollingMovementMethod());


        // Initialize SpeechSDK and request required permissions.
        try {
            // a unique number within the application to allow
            // correlating permission request responses with the request.
            int permissionRequestId = 8;

            // Request permissions needed for speech recognition - should move the subsequent Speech SDK stuff to a callback..
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{RECORD_AUDIO, INTERNET}, permissionRequestId);
        }
        catch(Exception ex) {
            Log.e("AzuLT", "Could not get the requested permissions. " + ex.toString());
            transcriptionTextView.setText("Could not get the requested permissions");
            return;
        }

        clearTextBox();
        content.clear();

        // Show the "no config" text by default, so that if stuff goes haywire the user will still see this message.
        //transcriptionTextView.setText(R.string.noSpeechConfig);

        // create config

        setupSpeechConfig();

        langButton = findViewById(R.id.changeLanguageButton);
        langButton.setText("Swedish");


        // Prevent the screen from turning off
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        // Make the divider between own & transcribed text draggable
        View dv = (View) findViewById(R.id.divider);
        dv.setOnTouchListener( (view, motionEvent) -> {
            switch (motionEvent.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    break;
                case MotionEvent.ACTION_MOVE:
                    int height = ((ConstraintLayout) findViewById(R.id.mainview)).getHeight();
                    Guideline guideLine = (Guideline) findViewById(R.id.guideline);
                    ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) guideLine.getLayoutParams();
                    params.guidePercent = (motionEvent.getRawY()) / height;

                    // prevent accidentally sliding the handle offscreen
                    if(params.guidePercent > 0.9f)
                        params.guidePercent = 0.9f;
                    if(params.guidePercent < 0.1f)
                        params.guidePercent = 0.1f;

                    guideLine.setLayoutParams(params);

                    break;
                default:
                    return false;
            }
            return true;
        });

        // Hide the own text.
        doHideOwnText();

    }



    void setupSpeechConfig() {
        SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(this);
        String newSpeechRegion = sharedPreferences.getString("region", "");
        String newSpeechSubscriptionKey = sharedPreferences.getString("key", "");

        if(!newSpeechRegion.equals(speechRegion) || !newSpeechSubscriptionKey.equals(speechSubscriptionKey))
        {
            // Azure resource settings was changed - recreate the speech recognizer
            speechRegion = newSpeechRegion;
            speechSubscriptionKey = newSpeechSubscriptionKey;
            try {
                speechConfig = SpeechConfig.fromSubscription(speechSubscriptionKey, speechRegion);
            }
            catch(Exception ex)
            {
                TextView tv = findViewById(R.id.transcribedTextView);
                tv.setText("Error in Azure configuration: " + ex.getMessage() );
                Log.i("AzuLT",ex.getMessage());
                speechConfig = null;
            }

            setupRecognizer(primaryLanguageSelected ? primaryLanguage : secondaryLanguage);

        }
    }


    private void setupRecognizer(String language)
    {
        if(speechConfig == null)
        {
            // Speech Configuration was never successfully created
            // Likely due to no valid Azure config setup
            // e.g. when running the app for the first time
            // Notify the user and call it a day.
            transcriptionTextView.setText(R.string.noSpeechConfig);
            return;
        }
        
        try {

            // TODO: restore the audio stream stuff, and handle it properly through pauses/restarts etc.
            //audioInput = AudioConfig.fromStreamInput(createMicrophoneStream());
            //reco = new SpeechRecognizer(speechConfig, audioInput);
            
            
            reco = new SpeechRecognizer(speechConfig, language);

            // Set the session ID so that we can quickly switch between different languages
            reco.sessionStarted.addEventListener((h, sessionEventArgs) -> {
                currentSessionID = sessionEventArgs.getSessionId();
                Log.i("AzuLT", "New session started with ID: " + currentSessionID);
            });

            reco.canceled.addEventListener((h, speechRecognitionCanceledEventArgs) -> {
                continuousListeningStarted = false;
                Log.i("stt", speechRecognitionCanceledEventArgs.getErrorCode().toString());
                CancellationErrorCode errorcode = speechRecognitionCanceledEventArgs.getErrorCode();
                if(errorcode == CancellationErrorCode.ConnectionFailure)
                    transcriptionTextView.setText(R.string.connection_failure);
                else if (errorcode == CancellationErrorCode.AuthenticationFailure)
                    transcriptionTextView.setText(R.string.authentication_failure);
                else
                    transcriptionTextView.setText(speechRecognitionCanceledEventArgs.getErrorCode().toString());

            });


            reco.recognizing.addEventListener((o, speechRecognitionResultEventArgs) -> {
                if(speechRecognitionResultEventArgs.getSessionId().equals(currentSessionID)) {
                    final String s = speechRecognitionResultEventArgs.getResult().getText();
                    //Log.i("stt", "Intermediate result received: " + s);
                    content.add(s);
                    setRecognizedText(TextUtils.join(" ", content));
                    content.remove(content.size() - 1);
                }
            });

            reco.recognized.addEventListener((o, speechRecognitionResultEventArgs) -> {
                if(speechRecognitionResultEventArgs.getSessionId().equals(currentSessionID)) {
                    final String s = speechRecognitionResultEventArgs.getResult().getText();
                    //Log.i("stt", "Final result received: " + s);
                    content.add(s);
                    setRecognizedText(TextUtils.join(" ", content));
                }
            });

            final Future<Void> task = reco.startContinuousRecognitionAsync();

            // TODO: check if this really is needed? can't we just go ahead and set the bool immediately?
            setOnTaskCompletedListener(task, result -> {
                continuousListeningStarted = true;
            });
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            displayException(ex);
        }

    }

    public void onSettingsClick(View v)
    {
        if(reco != null)
            reco.stopContinuousRecognitionAsync();

        // clear the textview, so that error messages don't remain and confuse
        // the user while waiting for new voice input
        clearTextBox();

        Intent myIntent = new Intent(this, SettingsActivity.class);
        startActivity(myIntent);
    }

    private void doShowOwnText()
    {
        EditText ownTextView = (EditText) findViewById(R.id.owntext);
        View dividerView = findViewById(R.id.divider);
        Guideline guideLine = (Guideline) findViewById(R.id.guideline);
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) guideLine.getLayoutParams();

        params.guidePercent = ownTextScreenPercentage;
        guideLine.setLayoutParams(params);
        ownTextView.setVisibility(View.VISIBLE);
        dividerView.setVisibility(View.VISIBLE);
        transcriptionTextView.setAlpha(0.25f);  // deemphasize the captioned text

        LinearLayout ll = (LinearLayout) findViewById(R.id.rootlinearlayout);
        ll.requestLayout();
        ConstraintLayout cl = (ConstraintLayout) findViewById(R.id.mainview);
        cl.requestLayout();
        // Make sure the last line is visible - without this the TextView will scroll up to the beginning(?!)
        // Still not perfect, TODO: fix this properly...
        transcriptionTextView.requestLayout();
        transcriptionTextView.computeScroll();
        transcriptionTextView.bringPointIntoView(transcriptionTextView.length()+25);
        // These mystical incantations shows the keyboard...
        ownTextView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(ownTextView, InputMethodManager.SHOW_IMPLICIT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);


    }

    private void doHideOwnText()
    {
        EditText ownTextView = (EditText) findViewById(R.id.owntext);
        View dividerView = findViewById(R.id.divider);
        Guideline guideLine = (Guideline) findViewById(R.id.guideline);
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) guideLine.getLayoutParams();

        ownTextScreenPercentage = params.guidePercent;
        params.guidePercent = 0.0f;  // Ensure that the transcribed text is placed at the top
        guideLine.setLayoutParams(params);
        ownTextView.setVisibility(View.GONE);
        dividerView.setVisibility(View.GONE);
        transcriptionTextView.setAlpha(1.0f);
    }

    public void onShowHideClick(View v)
    {
        Button btn = (Button) findViewById(R.id.showhideButton);
        showOwnText = !showOwnText;
        if(showOwnText)
        {
            btn.setText("Hide Own text");
            doShowOwnText();
        }
        else
        {
            btn.setText("Show Own text");
            doHideOwnText();
        }
    }





    private void displayException(Exception ex) {
        transcriptionTextView.setText(ex.getMessage() + System.lineSeparator() + TextUtils.join(System.lineSeparator(), ex.getStackTrace()));
    }

    private void clearTextBox() {
        content.clear();
        AppendTextLine("", true);
    }

    private void setRecognizedText(final String s) {
        AppendTextLine(s, true);
    }

    private void AppendTextLine(final String s, final Boolean erase) {
        MainActivity.this.runOnUiThread(  () -> {
            if (erase) {
                transcriptionTextView.setText(s);
            } else {
                String txt = transcriptionTextView.getText().toString();
                transcriptionTextView.setText(txt + System.lineSeparator() + s);
            }

            // Make sure the added text is visible, by scrolling
            transcriptionTextView.bringPointIntoView(transcriptionTextView.length());
        }  );
    }



    private <T> void setOnTaskCompletedListener(Future<T> task, OnTaskCompletedListener<T> listener) {
        s_executorService.submit(() -> {
            T result = task.get();
            listener.onCompleted(result);
            return null;
        });
    }

    private interface OnTaskCompletedListener<T> {
        void onCompleted(T taskResult);
    }



    private static ExecutorService s_executorService;
    static {
        s_executorService = Executors.newCachedThreadPool();
    }

}
