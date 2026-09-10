package com.example.voskassistant;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final int SAMPLE_RATE = 16000;
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final String MODEL_NAME = "vosk-model-small-ru-0.22";

    private TextView tvResult;
    private Button btnStart;

    private Model model;
    private Recognizer recognizer;
    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private Thread recordingThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvResult = findViewById(R.id.tv_result);
        btnStart = findViewById(R.id.btn_start);

        btnStart.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startRecording();
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
        } else {
            initVosk();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initVosk();
            } else {
                Toast.makeText(this, "Нужно разрешение на микрофон", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void initVosk() {
        new Thread(() -> {
            try {
                File modelDir = new File(getFilesDir(), MODEL_NAME);
                if (!modelDir.exists()) {
                    copyAssetFolder(MODEL_NAME, modelDir);
                }
                model = new Model(modelDir.getAbsolutePath());
                recognizer = new Recognizer(model, SAMPLE_RATE);
                runOnUiThread(() -> {
                    tvResult.setText("Vosk готов. Модель загружена.");
                    Toast.makeText(this, "Vosk готов", Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvResult.setText("Ошибка Vosk: " + e.getMessage());
                    Toast.makeText(this, "Ошибка Vosk: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvResult.setText("Критическая ошибка: " + e.getMessage());
                    Toast.makeText(this, "Критическая ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void copyAssetFolder(String srcFolder, File dstFolder) throws IOException {
        String[] files = getAssets().list(srcFolder);
        if (files == null || files.length == 0) {
            copyAssetFile(srcFolder, dstFolder);
        } else {
            dstFolder.mkdirs();
            for (String file : files) {
                String srcPath = srcFolder + "/" + file;
                File dstFile = new File(dstFolder, file);
                copyAssetFolder(srcPath, dstFile);
            }
        }
    }

    private void copyAssetFile(String srcPath, File dstFile) throws IOException {
        try (InputStream in = getAssets().open(srcPath);
             OutputStream out = new FileOutputStream(dstFile)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        }
    }

    private void startRecording() {
        if (model == null || recognizer == null) {
            Toast.makeText(this, "Vosk ещё не готов", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT) * 2;

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "Не удалось открыть микрофон", Toast.LENGTH_LONG).show();
            return;
        }

        isRecording = true;
        btnStart.setText("Остановить");
        audioRecord.startRecording();

        recordingThread = new Thread(() -> {
            short[] buffer = new short[1024];
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    if (recognizer.acceptWaveForm(buffer, read)) {
                        showResult(recognizer.getResult());
                    } else {
                        showResult(recognizer.getPartialResult());
                    }
                }
            }
        });
        recordingThread.start();
    }

    private void stopRecording() {
        isRecording = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        if (recordingThread != null) {
            try { recordingThread.join(); } catch (InterruptedException ignored) {}
            recordingThread = null;
        }
        btnStart.setText("Начать слушать");
    }

    private void showResult(String jsonResult) {
        try {
            JSONObject json = new JSONObject(jsonResult);
            String text = json.optString("text", "");
            if (!text.isEmpty()) {
                runOnUiThread(() -> tvResult.setText(text));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isRecording) stopRecording();
        if (recognizer != null) recognizer.close();
        if (model != null) model.close();
    }
}
