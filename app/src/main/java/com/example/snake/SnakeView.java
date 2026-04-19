package com.example.snake;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Vibrator;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class SnakeView extends SurfaceView implements Runnable {
    private int SNAKE_SIZE;
    private Thread gameThread;
    private volatile boolean isPlaying;
    private final Paint paint;
    private final SurfaceHolder holder;
    private int screenWidth, screenHeight;
    private int effectiveWidth, effectiveHeight;

    private int snakeX, snakeY;
    private int foodX, foodY;
    private int specialFoodX, specialFoodY;
    private boolean isSpecialFoodActive = false;
    private int specialFoodTimer = 0;

    private int currentDirection = 3; 
    private final Queue<Integer> directionQueue = new LinkedList<>();

    private enum GameState { MENU, MAP_SELECT, SPEED_SELECT, PLAYING, PAUSED, GAME_OVER, SETTINGS, HIGH_SCORES, ABOUT }
    private GameState currentState = GameState.MENU;

    private enum MapType { CLASSIC, BOX, TUNNEL, MILL, RAILS, APARTMENT }
    private MapType currentMap;

    private enum Language { HEBREW, ENGLISH }
    private Language currentLanguage;

    private int speedLevel; // 0-7
    private boolean vibrationEnabled;
    private boolean isResumable = false;
    private boolean isNewHighScoreSession = false;

    private int score = 0;
    private int highScore;
    private final ArrayList<int[]> snakeParts;
    private final List<int[]> obstacles = new ArrayList<>();
    private final SharedPreferences prefs;
    private float touchStartX, touchStartY;
    private int selectedMenuItem = -1;
    private boolean swipeTriggered = false;
    private final Vibrator vibrator;
    private long lastUpdateTime = 0;

    public SnakeView(Context context) {
        super(context);
        holder = getHolder();
        paint = new Paint();
        paint.setAntiAlias(true);

        prefs = context.getSharedPreferences("SnakeGame", Context.MODE_PRIVATE);
        highScore = prefs.getInt("high_score", 0);
        vibrationEnabled = prefs.getBoolean("vibration_enabled", true);
        speedLevel = prefs.getInt("speed_level", 4);
        currentMap = MapType.values()[prefs.getInt("map_type", 0)];
        currentLanguage = Language.values()[prefs.getInt("language", 0)];
        
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        updateScreenSize();
        snakeParts = new ArrayList<>();

        resetGame();
        isResumable = false;
        loadGameState(); // טעינת מצב קודם אם קיים
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
    }

    private void updateScreenSize() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            wm.getDefaultDisplay().getMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            int numCols = 15;
            SNAKE_SIZE = screenWidth / numCols;
            effectiveWidth = screenWidth;
            effectiveHeight = screenHeight;
        }
    }

    private void initMap() {
        obstacles.clear();
        int cols = effectiveWidth / SNAKE_SIZE;
        int rows = effectiveHeight / SNAKE_SIZE;
        switch (currentMap) {
            case TUNNEL:
                for (int i = cols / 4; i < 3 * cols / 4; i++) {
                    obstacles.add(new int[]{i * SNAKE_SIZE, (rows / 3) * SNAKE_SIZE});
                    obstacles.add(new int[]{i * SNAKE_SIZE, (2 * rows / 3) * SNAKE_SIZE});
                }
                break;
            case MILL:
                int midX = cols / 2; int midY = rows / 2;
                for (int i = -4; i <= 4; i++) {
                    obstacles.add(new int[]{(midX + i) * SNAKE_SIZE, (midY + i) * SNAKE_SIZE});
                    obstacles.add(new int[]{(midX + i) * SNAKE_SIZE, (midY - i) * SNAKE_SIZE});
                }
                break;
            case RAILS:
                for (int i = 0; i < cols; i++) {
                    if (i % 4 != 0) {
                        obstacles.add(new int[]{i * SNAKE_SIZE, (rows / 4) * SNAKE_SIZE});
                        obstacles.add(new int[]{i * SNAKE_SIZE, (3 * rows / 4) * SNAKE_SIZE});
                    }
                }
                break;
            case APARTMENT:
                for (int i = 0; i < rows; i++) {
                    if (i != rows/2 && i != rows/2-1) obstacles.add(new int[]{(cols/2) * SNAKE_SIZE, i * SNAKE_SIZE});
                }
                for (int i = 0; i < cols; i++) {
                    if (i != cols/2 && i != cols/2-1) obstacles.add(new int[]{i * SNAKE_SIZE, (rows/2) * SNAKE_SIZE});
                }
                break;
            default: break;
        }
    }

    private void resetGame() {
        snakeX = (effectiveWidth / 2) / SNAKE_SIZE * SNAKE_SIZE;
        snakeY = (effectiveHeight / 2) / SNAKE_SIZE * SNAKE_SIZE;
        isSpecialFoodActive = false;
        initMap();
        spawnFood();
        currentDirection = 3;
        directionQueue.clear();
        score = 0;
        isNewHighScoreSession = false;
        snakeParts.clear();
        snakeParts.add(new int[]{snakeX, snakeY});
        isResumable = true;
    }

    private void spawnFood() {
        boolean valid;
        float cols = (float) effectiveWidth / SNAKE_SIZE;
        float rows = (float) effectiveHeight / SNAKE_SIZE;
        do {
            valid = true;
            foodX = (int) (Math.random() * (cols - 2) + 1) * SNAKE_SIZE;
            foodY = (int) (Math.random() * (rows - 2) + 1) * SNAKE_SIZE;
            for (int[] wall : obstacles) { if (foodX == wall[0] && foodY == wall[1]) { valid = false; break; } }
            for (int[] part : snakeParts) { if (foodX == part[0] && foodY == part[1]) { valid = false; break; } }
            if (isSpecialFoodActive && foodX == specialFoodX && foodY == specialFoodY) valid = false;
        } while (!valid);
    }

    private void spawnSpecialFood() {
        boolean valid;
        isSpecialFoodActive = true;
        specialFoodTimer = 45; // כ-3.5 שניות (תלוי במהירות)
        float cols = (float) effectiveWidth / SNAKE_SIZE;
        float rows = (float) effectiveHeight / SNAKE_SIZE;
        do {
            valid = true;
            specialFoodX = (int) (Math.random() * (cols - 2) + 1) * SNAKE_SIZE;
            specialFoodY = (int) (Math.random() * (rows - 2) + 1) * SNAKE_SIZE;
            for (int[] wall : obstacles) { if (specialFoodX == wall[0] && specialFoodY == wall[1]) { valid = false; break; } }
            for (int[] part : snakeParts) { if (specialFoodX == part[0] && specialFoodY == part[1]) { valid = false; break; } }
            if (specialFoodX == foodX && specialFoodY == foodY) valid = false;
        } while (!valid);
    }

    private void vibrate(int duration) {
        if (!vibrationEnabled || vibrator == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(duration);
        }
    }

    private void update() {
        if (currentState != GameState.PLAYING) return;
        
        saveGameState(); // שמירה אוטומטית בכל צעד
        
        if (!directionQueue.isEmpty()) {
            Integer nextDir = directionQueue.poll();
            if (nextDir != null) {
                if ((nextDir == 0 && currentDirection != 1) || (nextDir == 1 && currentDirection != 0) ||
                    (nextDir == 2 && currentDirection != 3) || (nextDir == 3 && currentDirection != 2)) {
                    currentDirection = nextDir;
                }
            }
        }
        switch (currentDirection) {
            case 0: snakeY -= SNAKE_SIZE; break;
            case 1: snakeY += SNAKE_SIZE; break;
            case 2: snakeX -= SNAKE_SIZE; break;
            case 3: snakeX += SNAKE_SIZE; break;
        }
        if (currentMap == MapType.CLASSIC) {
            if (snakeX < 0) snakeX = effectiveWidth - SNAKE_SIZE; else if (snakeX >= effectiveWidth) snakeX = 0;
            if (snakeY < 0) snakeY = effectiveHeight - SNAKE_SIZE; else if (snakeY >= effectiveHeight) snakeY = 0;
        } else {
            if (snakeX < 0 || snakeX >= effectiveWidth || snakeY < 0 || snakeY >= effectiveHeight) { gameOver(); return; }
        }
        for (int[] wall : obstacles) { if (snakeX == wall[0] && snakeY == wall[1]) { gameOver(); return; } }
        for (int i = 1; i < snakeParts.size(); i++) {
            if (snakeX == snakeParts.get(i)[0] && snakeY == snakeParts.get(i)[1]) { gameOver(); return; }
        }
        snakeParts.add(0, new int[]{snakeX, snakeY});
        
        // בדיקת אכילת אוכל רגיל
        if (Math.abs(snakeX - foodX) < SNAKE_SIZE && Math.abs(snakeY - foodY) < SNAKE_SIZE) {
            score += 1;
            vibrate(40);
            checkHighScore();
            spawnFood();
            
            // סיכוי של 20% להופעת אוכל מיוחד בנוסף לרגיל
            if (!isSpecialFoodActive && Math.random() < 0.20) {
                spawnSpecialFood();
            }
        } 
        // בדיקת אכילת אוכל מיוחד
        else if (isSpecialFoodActive && Math.abs(snakeX - specialFoodX) < SNAKE_SIZE && Math.abs(snakeY - specialFoodY) < SNAKE_SIZE) {
            score += 5;
            vibrate(80);
            checkHighScore();
            isSpecialFoodActive = false;
        }
        else { 
            snakeParts.remove(snakeParts.size() - 1); 
        }

        if (isSpecialFoodActive) {
            specialFoodTimer--;
            if (specialFoodTimer <= 0) {
                isSpecialFoodActive = false;
            }
        }
    }

    private void checkHighScore() {
        if (score > highScore) { 
            if (!isNewHighScoreSession && score > 0) isNewHighScoreSession = true;
            highScore = score; 
            prefs.edit().putInt("high_score", highScore).apply(); 
        }
    }

    private void gameOver() {
        currentState = GameState.GAME_OVER;
        isResumable = false;
        vibrate(200);
        
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove("snake_parts");
        editor.putInt("last_state", GameState.GAME_OVER.ordinal());
        editor.apply();
    }

    private void saveGameState() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("last_state", currentState.ordinal());
        if (currentState == GameState.PLAYING || currentState == GameState.PAUSED) {
            editor.putInt("snake_x", snakeX);
            editor.putInt("snake_y", snakeY);
            editor.putInt("food_x", foodX);
            editor.putInt("food_y", foodY);
            editor.putInt("score", score);
            editor.putInt("direction", currentDirection);
            
            // שמירת חלקי הנחש כמחרוזת (x,y;x,y...)
            StringBuilder sb = new StringBuilder();
            for (int[] part : snakeParts) {
                sb.append(part[0]).append(",").append(part[1]).append(";");
            }
            editor.putString("snake_parts", sb.toString());
        }
        editor.apply();
    }

    private void loadGameState() {
        if (prefs.contains("snake_parts")) {
            snakeX = prefs.getInt("snake_x", snakeX);
            snakeY = prefs.getInt("snake_y", snakeY);
            foodX = prefs.getInt("food_x", foodX);
            foodY = prefs.getInt("food_y", foodY);
            score = prefs.getInt("score", 0);
            currentDirection = prefs.getInt("direction", 3);
            
            String partsStr = prefs.getString("snake_parts", "");
            if (!partsStr.isEmpty()) {
                snakeParts.clear();
                String[] parts = partsStr.split(";");
                for (String p : parts) {
                    if (p.isEmpty()) continue;
                    String[] coords = p.split(",");
                    snakeParts.add(new int[]{Integer.parseInt(coords[0]), Integer.parseInt(coords[1])});
                }
            }
            // הסרנו את currentState = GameState.PAUSED כדי שייפתח בתפריט
            isResumable = true;
        }
    }

    private void draw() {
        if (holder.getSurface().isValid()) {
            Canvas canvas = holder.lockCanvas();
            if (canvas == null) return;
            canvas.drawColor(Color.parseColor("#001a33"));
            switch (currentState) {
                case MENU: drawMenu(canvas); break;
                case MAP_SELECT: drawMapSelect(canvas); break;
                case SPEED_SELECT: drawSpeedSelect(canvas); break;
                case SETTINGS: drawSettings(canvas); break;
                case PLAYING: case PAUSED: drawGame(canvas); break;
                case GAME_OVER: drawGameOver(canvas); break;
                case HIGH_SCORES: drawHighScores(canvas); break;
                case ABOUT: drawAbout(canvas); break;
            }
            holder.unlockCanvasAndPost(canvas);
        }
    }

    private String t(int hebResId, int engResId) { 
        return getContext().getString(currentLanguage == Language.HEBREW ? hebResId : engResId); 
    }

    private void drawNokiaHeader(Canvas canvas, String title) {
        // גראדיאנט עדין לכותרת
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#008b9a"));
        canvas.drawRect(0, 0, screenWidth, SNAKE_SIZE * 2, paint);
        paint.setColor(Color.parseColor("#00bcd4"));
        canvas.drawRect(0, 0, screenWidth, SNAKE_SIZE * 1.8f, paint);
        
        paint.setColor(Color.WHITE);
        paint.setTextSize(SNAKE_SIZE * 0.9f);
        paint.setFakeBoldText(true);
        canvas.drawText(title, SNAKE_SIZE * 0.5f, SNAKE_SIZE * 1.3f, paint);
        paint.setFakeBoldText(false);
    }

    private void drawNokiaFooter(Canvas canvas, String leftBtn) {
        paint.setColor(Color.parseColor("#4da6ff"));
        paint.setTextSize(SNAKE_SIZE * 1.1f);
        if (leftBtn != null) canvas.drawText(leftBtn, SNAKE_SIZE, screenHeight - SNAKE_SIZE, paint);
        canvas.drawText(t(R.string.btn_back_heb, R.string.btn_back_eng), screenWidth - SNAKE_SIZE * 4, screenHeight - SNAKE_SIZE, paint);
    }

    private void drawMenu(Canvas canvas) {
        // ציור לוגו סנייק מעוצב (ציור וקטורי של נחש)
        float logoY = SNAKE_SIZE * 2.5f;
        paint.setColor(Color.parseColor("#00bcd4"));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(SNAKE_SIZE / 3f);
        
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(screenWidth * 0.2f, logoY);
        path.quadTo(screenWidth * 0.4f, logoY - SNAKE_SIZE, screenWidth * 0.5f, logoY);
        path.quadTo(screenWidth * 0.6f, logoY + SNAKE_SIZE, screenWidth * 0.8f, logoY);
        canvas.drawPath(path, paint);
        
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setTextSize(SNAKE_SIZE * 1.5f);
        paint.setFakeBoldText(true);
        String title = "SNAKE PRO";
        canvas.drawText(title, screenWidth / 2f - paint.measureText(title) / 2f, logoY + SNAKE_SIZE * 2.2f, paint);
        paint.setFakeBoldText(false);

        ArrayList<String> items = new ArrayList<>();
        if (isResumable) items.add(t(R.string.menu_continue_heb, R.string.menu_continue_eng));
        items.add(t(R.string.menu_new_game_heb, R.string.menu_new_game_eng));
        items.add(t(R.string.menu_maps_heb, R.string.menu_maps_eng));
        items.add(t(R.string.menu_speed_heb, R.string.menu_speed_eng));
        items.add(t(R.string.menu_settings_heb, R.string.menu_settings_eng));
        items.add(t(R.string.menu_high_score_heb, R.string.menu_high_score_eng));
        items.add(t(R.string.menu_about_heb, R.string.menu_about_eng));

        paint.setTextSize(SNAKE_SIZE * 1.0f);
        float menuStartY = logoY + SNAKE_SIZE * 4.5f;
        for (int i = 0; i < items.size(); i++) {
            if (i == selectedMenuItem) {
                paint.setColor(Color.parseColor("#3399FF"));
                canvas.drawRect(0, menuStartY - SNAKE_SIZE + (i * SNAKE_SIZE * 1.5f), screenWidth, menuStartY + SNAKE_SIZE * 0.5f + (i * SNAKE_SIZE * 1.5f), paint);
                paint.setColor(Color.BLACK);
            } else {
                paint.setColor(Color.WHITE);
            }
            canvas.drawText(items.get(i), screenWidth - paint.measureText(items.get(i)) - SNAKE_SIZE, menuStartY + (i * SNAKE_SIZE * 1.5f), paint);
        }
        drawNokiaFooter(canvas, t(R.string.btn_select_heb, R.string.btn_select_eng));
    }

    private void drawMapSelect(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.select_map_heb, R.string.select_map_eng));
        MapType[] maps = MapType.values();
        for (int i = 0; i < maps.length; i++) {
            if (maps[i] == currentMap) {
                paint.setColor(Color.parseColor("#00bcd4"));
                canvas.drawRect(0, SNAKE_SIZE * 2.5f + (i * SNAKE_SIZE * 1.5f), screenWidth, SNAKE_SIZE * 4 + (i * SNAKE_SIZE * 1.5f), paint);
                paint.setColor(Color.WHITE);
            } else paint.setColor(Color.WHITE);
            canvas.drawText(maps[i].name(), SNAKE_SIZE, SNAKE_SIZE * 3.5f + (i * SNAKE_SIZE * 1.5f), paint);
        }
        drawNokiaFooter(canvas, t(R.string.btn_select_heb, R.string.btn_select_eng));
    }

    private void drawSpeedSelect(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.menu_speed_heb, R.string.menu_speed_eng));
        float barW = screenWidth * 0.8f; float segW = barW / 8;
        float sX = (screenWidth - barW) / 2; float sY = screenHeight / 2 - SNAKE_SIZE;
        for (int i = 0; i < 8; i++) {
            paint.setColor(i <= speedLevel ? Color.GREEN : Color.parseColor("#003366"));
            canvas.drawRect(sX + (i * segW) + 2, sY, sX + ((i + 1) * segW) - 2, sY + SNAKE_SIZE * 2, paint);
        }
        drawNokiaFooter(canvas, t(R.string.btn_select_heb, R.string.btn_select_eng));
    }

    private void drawSettings(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.menu_settings_heb, R.string.menu_settings_eng));
        paint.setTextSize(SNAKE_SIZE * 0.9f); paint.setColor(Color.WHITE);
        canvas.drawText(t(R.string.lang_label_heb, R.string.lang_label_eng), SNAKE_SIZE, SNAKE_SIZE * 4, paint);
        String vibStatus = vibrationEnabled ? t(R.string.on_heb, R.string.on_eng) : t(R.string.off_heb, R.string.off_eng);
        canvas.drawText(t(R.string.vibration_label_heb, R.string.vibration_label_eng) + vibStatus, SNAKE_SIZE, SNAKE_SIZE * 6, paint);
        drawNokiaFooter(canvas, t(R.string.btn_change_heb, R.string.btn_change_eng));
    }

    private void drawGame(Canvas canvas) {
        canvas.drawColor(Color.parseColor("#001a33"));
        
        // ציור גריד עדין
        paint.setColor(Color.parseColor("#00264d"));
        paint.setStrokeWidth(1);
        for(int i=0; i<=effectiveWidth; i+=SNAKE_SIZE) canvas.drawLine(i, 0, i, effectiveHeight, paint);
        for(int i=0; i<=effectiveHeight; i+=SNAKE_SIZE) canvas.drawLine(0, i, effectiveWidth, i, paint);

        // ציור מכשולים עם אפקט תלת-ממד (Bevel)
        for (int[] wall : obstacles) {
            paint.setColor(Color.parseColor("#004d99"));
            drawRoundRectCompat(canvas, wall[0] + 2, wall[1] + 2, wall[0] + SNAKE_SIZE - 2, wall[1] + SNAKE_SIZE - 2, 4, paint);
            paint.setColor(Color.parseColor("#0066cc")); // אור
            canvas.drawRect(wall[0] + 2, wall[1] + 2, wall[0] + SNAKE_SIZE - 2, wall[1] + 6, paint);
        }

        // ציור אוכל עם פעימה וברק מבוססי זמן אמת
        long time = System.currentTimeMillis();
        float pulse = (float) Math.sin(time * 0.01) * 5;
        
        // תמיד מציירים אוכל רגיל - אדום
        paint.setColor(Color.RED);
        drawRoundRectCompat(canvas, foodX + 6 - pulse, foodY + 8 - pulse, foodX + SNAKE_SIZE - 6 + pulse, foodY + SNAKE_SIZE - 2 + pulse, 12, paint);
        paint.setColor(Color.WHITE); // ברק
        canvas.drawCircle(foodX + SNAKE_SIZE * 0.3f, foodY + SNAKE_SIZE * 0.4f, 3, paint);
        paint.setColor(Color.parseColor("#32CD32")); // עלה
        canvas.drawRect(foodX + SNAKE_SIZE/2f, foodY + 2, foodX + SNAKE_SIZE/2f + 4, foodY + 8, paint);

        if (isSpecialFoodActive) {
            // אוכל מיוחד - זהוב, מנצנץ ויוקרתי
            paint.setColor(Color.parseColor("#FFD700"));
            drawRoundRectCompat(canvas, specialFoodX + 2 - pulse, specialFoodY + 2 - pulse, specialFoodX + SNAKE_SIZE - 2 + pulse, specialFoodY + SNAKE_SIZE - 2 + pulse, 15, paint);
            
            // הילה זהובה חיצונית
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4);
            paint.setColor(Color.argb(180, 255, 215, 0));
            canvas.drawCircle(specialFoodX + SNAKE_SIZE/2f, specialFoodY + SNAKE_SIZE/2f, SNAKE_SIZE/2f + pulse * 1.5f + 5, paint);
            
            // ניצוצות מסתובבים (אפקט חלקיקים)
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            float angle = (time % 1000) / 1000f * 360;
            for (int i = 0; i < 4; i++) {
                double rad = Math.toRadians(angle + (i * 90));
                float sx = (float) (specialFoodX + SNAKE_SIZE/2f + Math.cos(rad) * (SNAKE_SIZE/1.5f));
                float sy = (float) (specialFoodY + SNAKE_SIZE/2f + Math.sin(rad) * (SNAKE_SIZE/1.5f));
                canvas.drawCircle(sx, sy, 3, paint);
            }

            // סטופר עליון (פס זמן מתחת לניקוד)
            float timerWidth = screenWidth * 0.5f;
            float timerX = (screenWidth - timerWidth) / 2f;
            float timerY = SNAKE_SIZE * 0.8f; // מיקום בתוך ה-Header
            
            // רקע הסטופר (כהה שקוף)
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(80, 0, 0, 0));
            drawRoundRectCompat(canvas, timerX, timerY, timerX + timerWidth, timerY + 12, 6, paint);
            
            // מילוי הסטופר (זהב נוצץ)
            paint.setColor(Color.parseColor("#FFD700"));
            float currentProgress = (specialFoodTimer / 45f) * timerWidth;
            drawRoundRectCompat(canvas, timerX, timerY, timerX + currentProgress, timerY + 12, 6, paint);
            
            // טיימר ספרתי בפורמט 0.00
            paint.setTextSize(SNAKE_SIZE * 0.5f);
            paint.setFakeBoldText(true);
            paint.setColor(Color.WHITE);
            // חישוב שניות משוער (לפי קצב רענון ממוצע של 80-100ms)
            double secondsLeft = specialFoodTimer * 0.08; 
            @SuppressLint("DefaultLocale") String timeStr = String.format("%.2f", secondsLeft);
            canvas.drawText(timeStr, timerX + timerWidth + 15, timerY + 10, paint);
            paint.setFakeBoldText(false);
        }
        
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);

        // ציור הנחש
        for (int i = 0; i < snakeParts.size(); i++) {
            int[] p = snakeParts.get(i);
            if (i == 0) {
                // ראש לבן ובוהק
                paint.setColor(Color.WHITE);
                drawRoundRectCompat(canvas, p[0] + 1, p[1] + 1, p[0] + SNAKE_SIZE - 1, p[1] + SNAKE_SIZE - 1, 8, paint);
                
                // עיניים זזות
                paint.setColor(Color.BLACK);
                float eS = SNAKE_SIZE / 10f;
                float o = SNAKE_SIZE * 0.2f; // Offset
                if (currentDirection == 3) { // ימינה
                    canvas.drawCircle(p[0] + SNAKE_SIZE - o, p[1] + o, eS, paint);
                    canvas.drawCircle(p[0] + SNAKE_SIZE - o, p[1] + SNAKE_SIZE - o, eS, paint);
                } else if (currentDirection == 2) { // שמאלה
                    canvas.drawCircle(p[0] + o, p[1] + o, eS, paint);
                    canvas.drawCircle(p[0] + o, p[1] + SNAKE_SIZE - o, eS, paint);
                } else if (currentDirection == 0) { // למעלה
                    canvas.drawCircle(p[0] + o, p[1] + o, eS, paint);
                    canvas.drawCircle(p[0] + SNAKE_SIZE - o, p[1] + o, eS, paint);
                } else { // למטה
                    canvas.drawCircle(p[0] + o, p[1] + SNAKE_SIZE - o, eS, paint);
                    canvas.drawCircle(p[0] + SNAKE_SIZE - o, p[1] + SNAKE_SIZE - o, eS, paint);
                }
            } else {
                // גוף בגווני טורקיז עם חיבורים
                paint.setColor(Color.parseColor(i % 2 == 0 ? "#E0F7FA" : "#B2EBF2"));
                drawRoundRectCompat(canvas, p[0] + 2, p[1] + 2, p[0] + SNAKE_SIZE - 2, p[1] + SNAKE_SIZE - 2, 6, paint);
            }
        }

        // אפקט LCD - קווי סריקה דקים מאוד
        paint.setColor(Color.BLACK);
        paint.setAlpha(15);
        for (int i = 0; i < screenHeight; i += 6) {
            canvas.drawLine(0, i, screenWidth, i, paint);
        }
        paint.setAlpha(255);

        // ניקוד
        if (isNewHighScoreSession) {
            paint.setColor(Color.YELLOW);
            paint.setTextSize(SNAKE_SIZE * 0.8f);
            String msg = t(R.string.new_record_heb, R.string.new_record_eng);
            canvas.drawText(msg, screenWidth/2f - paint.measureText(msg)/2f, SNAKE_SIZE * 2.8f, paint);
            paint.setColor(Color.YELLOW);
        } else {
            paint.setColor(Color.WHITE);
        }
        
        paint.setTextSize(SNAKE_SIZE * 1.5f);
        paint.setFakeBoldText(true);
        canvas.drawText("" + score, screenWidth/2f - paint.measureText("" + score)/2f, SNAKE_SIZE * 1.8f, paint);
        paint.setFakeBoldText(false);

        if (currentState == GameState.PAUSED) {
            paint.setColor(Color.argb(150, 0, 0, 0));
            canvas.drawRect(0, 0, screenWidth, screenHeight, paint);
            paint.setColor(Color.parseColor("#3399FF"));
            canvas.drawRect(screenWidth * 0.15f, screenHeight/2f - SNAKE_SIZE * 1.5f, screenWidth * 0.85f, screenHeight/2f + SNAKE_SIZE * 1.5f, paint);
            paint.setColor(Color.WHITE);
            String txt = t(R.string.paused_heb, R.string.paused_eng);
            canvas.drawText(txt, screenWidth/2f - paint.measureText(txt)/2f, screenHeight/2f + SNAKE_SIZE * 0.4f, paint);
        }
    }

    private void drawHighScores(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.menu_high_score_heb, R.string.menu_high_score_eng));
        paint.setColor(Color.WHITE); paint.setTextSize(SNAKE_SIZE * 1.2f);
        canvas.drawText(t(R.string.best_score_heb, R.string.best_score_eng) + highScore, SNAKE_SIZE, SNAKE_SIZE * 5, paint);
        drawNokiaFooter(canvas, null);
    }

    private void drawAbout(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.menu_about_heb, R.string.menu_about_eng));
        paint.setColor(Color.WHITE); paint.setTextSize(SNAKE_SIZE * 0.8f);
        canvas.drawText("Snake Pro Nokia Style", SNAKE_SIZE, SNAKE_SIZE * 4, paint);
        canvas.drawText("Developed by The Creator YT", SNAKE_SIZE, SNAKE_SIZE * 6, paint);
        drawNokiaFooter(canvas, null);
    }

    private void drawGameOver(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.game_over_heb, R.string.game_over_eng));
        paint.setColor(Color.WHITE); paint.setTextSize(SNAKE_SIZE * 1.5f);
        canvas.drawText(t(R.string.score_label_heb, R.string.score_label_eng) + score, SNAKE_SIZE, screenHeight/2f, paint);
        drawNokiaFooter(canvas, t(R.string.btn_retry_heb, R.string.btn_retry_eng));
    }

    private void drawRoundRectCompat(Canvas canvas, float l, float t, float r, float b, float rx, Paint p) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            canvas.drawRoundRect(l, t, r, b, rx, rx, p);
        } else {
            // עבור אנדרואיד 4.4 ומטה (API < 21)
            android.graphics.RectF rect = new android.graphics.RectF(l, t, r, b);
            canvas.drawRoundRect(rect, rx, rx, p);
        }
    }

    @Override
    public void run() {
        while (isPlaying) {
            long currentTime = System.currentTimeMillis();
            
            // חישוב השהיית המשחק לפי הרמה
            int moveDelay = Math.max(40, 200 - (speedLevel * 20));

            if (currentState == GameState.PLAYING) {
                if (currentTime - lastUpdateTime >= moveDelay) {
                    update();
                    lastUpdateTime = currentTime;
                }
            } else {
                lastUpdateTime = currentTime; // איפוס זמן עדכון כשלא משחקים
            }

            // ציור תמיד מתבצע במהירות מקסימלית לחוויה חלקה
            draw();

            try {
                // השהיה קבועה של 16 מילישניות (בערך 60 FPS)
                Thread.sleep(16);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(); float y = event.getY();
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchStartX = x; touchStartY = y; swipeTriggered = false;
            
            // בדיקת לחיצה על כפתור "חזור" (צד ימין למטה)
            if (x > screenWidth - SNAKE_SIZE * 5 && y > screenHeight - SNAKE_SIZE * 3) {
                if (currentState == GameState.PLAYING) {
                    currentState = GameState.PAUSED;
                } else if (currentState == GameState.MENU) {
                    // אם בתפריט הראשי, יוצאים מהאפליקציה
                    if (getContext() instanceof android.app.Activity) {
                        ((android.app.Activity) getContext()).finish();
                    }
                } else {
                    currentState = GameState.MENU;
                }
                vibrate(30);
                return true;
            }

            if (currentState == GameState.MENU) {
                // חישוב המיקום המדויק של תחילת התפריט (logoY + 4.5*SNAKE_SIZE - SNAKE_SIZE)
                float menuYStart = SNAKE_SIZE * 6.0f; 
                selectedMenuItem = (int) ((y - menuYStart) / (SNAKE_SIZE * 1.5f));
                
                // הגנה מפני בחירה מחוץ לטווח
                if (y < menuYStart) selectedMenuItem = -1;
                return true;
            }

            if (currentState == GameState.MAP_SELECT) {
                int i = (int) ((y - SNAKE_SIZE * 2.5f) / (SNAKE_SIZE * 1.5f));
                if (i >= 0 && i < MapType.values().length) { currentMap = MapType.values()[i]; prefs.edit().putInt("map_type", i).apply(); }
            } else if (currentState == GameState.SPEED_SELECT) {
                if (y > screenHeight/2f - SNAKE_SIZE && y < screenHeight/2f + SNAKE_SIZE * 2) {
                    speedLevel = (int) ((x - screenWidth * 0.1f) / (screenWidth * 0.8f / 8));
                    if (speedLevel < 0) speedLevel = 0; if (speedLevel > 7) speedLevel = 7;
                    prefs.edit().putInt("speed_level", speedLevel).apply();
                }
            } else if (currentState == GameState.SETTINGS) {
                if (y > SNAKE_SIZE * 3 && y < SNAKE_SIZE * 5) { currentLanguage = (currentLanguage == Language.HEBREW) ? Language.ENGLISH : Language.HEBREW; prefs.edit().putInt("language", currentLanguage.ordinal()).apply(); }
                else if (y > SNAKE_SIZE * 5 && y < SNAKE_SIZE * 7) { vibrationEnabled = !vibrationEnabled; prefs.edit().putBoolean("vibration_enabled", vibrationEnabled).apply(); if (vibrationEnabled) vibrator.vibrate(50); }
            } else if (currentState == GameState.PAUSED || currentState == GameState.GAME_OVER) {
                if (currentState == GameState.GAME_OVER && x < screenWidth/2f) { resetGame(); currentState = GameState.PLAYING; }
                else currentState = GameState.PLAYING;
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (currentState == GameState.MENU && selectedMenuItem != -1) {
                int itemIndex = selectedMenuItem;
                selectedMenuItem = -1; // איפוס הבחירה

                if (isResumable) {
                    if (itemIndex == 0) { 
                        currentState = GameState.PAUSED; // טוען למסך השהיה במקום להתחיל מיד
                        return true; 
                    }
                    itemIndex--;
                }
                
                if (itemIndex == 0) { resetGame(); currentState = GameState.PLAYING; }
                else if (itemIndex == 1) currentState = GameState.MAP_SELECT;
                else if (itemIndex == 2) currentState = GameState.SPEED_SELECT;
                else if (itemIndex == 3) currentState = GameState.SETTINGS;
                else if (itemIndex == 4) currentState = GameState.HIGH_SCORES;
                else if (itemIndex == 5) currentState = GameState.ABOUT;
                vibrate(20);
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (currentState != GameState.PLAYING || swipeTriggered) return true;
            float dx = x - touchStartX; float dy = y - touchStartY;
            if (Math.abs(dx) > SNAKE_SIZE * 0.8f || Math.abs(dy) > SNAKE_SIZE * 0.8f) {
                int nD = -1; if (Math.abs(dx) > Math.abs(dy)) nD = (dx > 0) ? 3 : 2; else nD = (dy > 0) ? 1 : 0;
                if (nD != -1 && directionQueue.size() < 2) { directionQueue.add(nD); swipeTriggered = true; }
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (currentState == GameState.PLAYING) {
                currentState = GameState.PAUSED;
                return true;
            } else if (currentState == GameState.MENU) {
                // מאפשר למערכת לטפל בלחיצה (יציאה מהאפליקציה)
                return false;
            } else {
                // חוזר לתפריט הראשי מכל מסך אחר (הגדרות, מפות וכו')
                currentState = GameState.MENU;
                return true;
            }
        }
        if (currentState == GameState.PLAYING) {
            int nD;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP: nD = 0; break; 
                case KeyEvent.KEYCODE_DPAD_DOWN: nD = 1; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: nD = 2; break; 
                case KeyEvent.KEYCODE_DPAD_RIGHT: nD = 3; break;
                case KeyEvent.KEYCODE_P: currentState = GameState.PAUSED; return true;
                default: return super.onKeyDown(keyCode, event);
            }
            if (directionQueue.size() < 2) { directionQueue.add(nD); }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void pause() { 
        if (currentState == GameState.PLAYING) {
            currentState = GameState.PAUSED;
            saveGameState();
        }
        isPlaying = false; 
        try { 
            if (gameThread != null) gameThread.join(); 
        } catch (InterruptedException e) { 
            Thread.currentThread().interrupt(); 
        }
    }
    public void resume() { isPlaying = true; gameThread = new Thread(this); gameThread.start(); }
}
