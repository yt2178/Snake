package com.example.snake;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Vibrator;
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
    private final List<Integer> highScores = new ArrayList<>();
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
        loadHighScores();
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
            
            // חישוב גודל הנחש בצורה דינמית כדי שיתאים לכל סוגי המסכים:
            // ב-Qin 1S+ (240px) נקבל 20px למשבצת.
            // ב-Jelly 2 (480px) נקבל 40px למשבצת.
            // זה שומר על יחס של 12 עמודות לכל אורך הדרך.
            int numCols = 12; 
            SNAKE_SIZE = screenWidth / numCols;
            
            // הגדרת שטח המשחק האפקטיבי (מחושב לפי משבצות שלמות)
            // נשאיר מקום של 2 משבצות ל-Header ומשבצת וחצי ל-Footer
            effectiveWidth = screenWidth;
            int rowsInScreen = screenHeight / SNAKE_SIZE;
            effectiveHeight = (rowsInScreen - 4) * SNAKE_SIZE; 
        }
    }

    private void initMap() {
        obstacles.clear();
        int cols = effectiveWidth / SNAKE_SIZE;
        int rows = effectiveHeight / SNAKE_SIZE;
        switch (currentMap) {
            case BOX: {
                // קירות מסביב לכל הגבולות
                for (int i = 0; i < cols; i++) {
                    obstacles.add(new int[]{i * SNAKE_SIZE, 0});
                    obstacles.add(new int[]{i * SNAKE_SIZE, (rows - 1) * SNAKE_SIZE});
                }
                for (int i = 1; i < rows - 1; i++) {
                    obstacles.add(new int[]{0, i * SNAKE_SIZE});
                    obstacles.add(new int[]{(cols - 1) * SNAKE_SIZE, i * SNAKE_SIZE});
                }
                break;
            }
            case TUNNEL: {
                // שתי מנהרות אנכיות
                int gap = rows / 4;
                for (int i = 0; i < rows; i++) {
                    if (i < gap || i > rows - gap) continue;
                    obstacles.add(new int[]{(cols / 3) * SNAKE_SIZE, i * SNAKE_SIZE});
                    obstacles.add(new int[]{(2 * cols / 3) * SNAKE_SIZE, i * SNAKE_SIZE});
                }
                break;
            }
            case MILL: {
                // צורת X במרכז
                int midX = cols / 2;
                int midY = rows / 2;
                for (int i = -4; i <= 4; i++) {
                    if (i == 0) continue; // פתח במרכז ה-X
                    obstacles.add(new int[]{(midX + i) * SNAKE_SIZE, (midY + i) * SNAKE_SIZE});
                    obstacles.add(new int[]{(midX + i) * SNAKE_SIZE, (midY - i) * SNAKE_SIZE});
                }
                break;
            }
            case RAILS: {
                // מסילות אופקיות
                for (int r = 1; r < rows; r += 4) {
                    for (int c = 2; c < cols - 2; c++) {
                        if (c % 5 == 0) continue; // פתחים במסילות
                        obstacles.add(new int[]{c * SNAKE_SIZE, r * SNAKE_SIZE});
                    }
                }
                break;
            }
            case APARTMENT: {
                // חלוקה ל-4 חדרים
                for (int i = 0; i < rows; i++) {
                    if (i != rows / 4 && i != 3 * rows / 4)
                        obstacles.add(new int[]{(cols / 2) * SNAKE_SIZE, i * SNAKE_SIZE});
                }
                for (int i = 0; i < cols; i++) {
                    if (i != cols / 4 && i != 3 * cols / 4)
                        obstacles.add(new int[]{i * SNAKE_SIZE, (rows / 2) * SNAKE_SIZE});
                }
                break;
            }
            default:
                break;
        }
    }

    private void resetGame() {
        snakeX = (effectiveWidth / 2) / SNAKE_SIZE * SNAKE_SIZE;
        snakeY = (effectiveHeight / 2) / SNAKE_SIZE * SNAKE_SIZE;
        isSpecialFoodActive = false;
        initMap();
        currentDirection = 3;
        directionQueue.clear();
        score = 0;
        isNewHighScoreSession = false;
        snakeParts.clear();
        snakeParts.add(new int[]{snakeX, snakeY});
        spawnFood(); // הזזה לאחר אתחול חלקי הנחש כדי למנוע הופעת תפוח מתחת לנחש
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
        specialFoodTimer = 5000; // 5 שניות בדיוק
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
        
        int moveDelay = Math.max(40, 200 - (speedLevel * 20));
        
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
        if (snakeX == foodX && snakeY == foodY) {
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
        else if (isSpecialFoodActive && snakeX == specialFoodX && snakeY == specialFoodY) {
            int bonus = Math.max(1, (int) Math.ceil(specialFoodTimer / 1000.0));
            score += bonus;
            vibrate(80);
            checkHighScore();
            isSpecialFoodActive = false;
        }
        else { 
            snakeParts.remove(snakeParts.size() - 1); 
        }

        if (isSpecialFoodActive) {
            specialFoodTimer -= moveDelay;
            if (specialFoodTimer <= 0) {
                isSpecialFoodActive = false;
            }
        }
    }

    private void loadHighScores() {
        highScores.clear();
        String scoresStr = prefs.getString("high_scores_list", "");
        if (scoresStr.isEmpty()) {
            // טעינת שיא ישן אם קיים לצורך תאימות
            int oldScore = prefs.getInt("high_score", 0);
            if (oldScore > 0) highScores.add(oldScore);
        } else {
            String[] parts = scoresStr.split(",");
            for (String s : parts) {
                try {
                    highScores.add(Integer.parseInt(s));
                } catch (NumberFormatException e) {
                    // התעלמות משגיאות פורמט
                }
            }
        }
    }

    private void saveHighScores() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < highScores.size(); i++) {
            sb.append(highScores.get(i));
            if (i < highScores.size() - 1) sb.append(",");
        }
        prefs.edit().putString("high_scores_list", sb.toString()).apply();
    }

    private void checkHighScore() {
        if (score <= 0) return;
        
        boolean added = false;
        if (highScores.size() < 5) {
            highScores.add(score);
            added = true;
        } else if (score > highScores.get(highScores.size() - 1)) {
            highScores.set(highScores.size() - 1, score);
            added = true;
        }

        if (added) {
            java.util.Collections.sort(highScores, java.util.Collections.reverseOrder());
            saveHighScores();
            if (score >= highScores.get(0)) isNewHighScoreSession = true;
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
        // גראדיאנט יוקרתי לכותרת
        android.graphics.LinearGradient gradient = new android.graphics.LinearGradient(
            0, 0, 0, SNAKE_SIZE * 2f,
            Color.parseColor("#00bcd4"), Color.parseColor("#008b9a"),
            android.graphics.Shader.TileMode.CLAMP
        );
        paint.setShader(gradient);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, screenWidth, SNAKE_SIZE * 2f, paint);
        paint.setShader(null);

        // קו הפרדה דק בתחתית הכותרת
        paint.setColor(Color.parseColor("#004d40"));
        paint.setStrokeWidth(2);
        canvas.drawLine(0, SNAKE_SIZE * 2f, screenWidth, SNAKE_SIZE * 2f, paint);
        
        paint.setColor(Color.WHITE);
        paint.setTextSize(SNAKE_SIZE * 0.9f);
        paint.setFakeBoldText(true);
        paint.setShadowLayer(3, 1, 1, Color.BLACK); // הוספת צל עדין לטקסט
        
        float xPos = (currentLanguage == Language.HEBREW) ? 
                     screenWidth - paint.measureText(title) - SNAKE_SIZE * 0.5f : 
                     SNAKE_SIZE * 0.5f;
                     
        canvas.drawText(title, xPos, SNAKE_SIZE * 1.3f, paint);
        paint.clearShadowLayer();
        paint.setFakeBoldText(false);
    }

    private void drawNokiaFooter(Canvas canvas, String leftBtn) {
        paint.setColor(Color.parseColor("#4da6ff"));
        paint.setTextSize(SNAKE_SIZE * 1.1f);
        
        String backBtn = t(R.string.btn_back_heb, R.string.btn_back_eng);
        
        if (currentLanguage == Language.HEBREW) {
            // בעברית: Select/Change בימין, Back בשמאל
            if (leftBtn != null) canvas.drawText(leftBtn, screenWidth - paint.measureText(leftBtn) - SNAKE_SIZE, screenHeight - SNAKE_SIZE, paint);
            canvas.drawText(backBtn, SNAKE_SIZE, screenHeight - SNAKE_SIZE, paint);
        } else {
            // באנגלית: Select/Change בשמאל, Back בימין
            if (leftBtn != null) canvas.drawText(leftBtn, SNAKE_SIZE, screenHeight - SNAKE_SIZE, paint);
            canvas.drawText(backBtn, screenWidth - paint.measureText(backBtn) - SNAKE_SIZE, screenHeight - SNAKE_SIZE, paint);
        }
    }

    private void drawMenu(Canvas canvas) {
        // רקע מדורג למסך התפריט
        android.graphics.LinearGradient bgGradient = new android.graphics.LinearGradient(
            0, 0, 0, screenHeight,
            Color.parseColor("#001a33"), Color.parseColor("#000d1a"),
            android.graphics.Shader.TileMode.CLAMP
        );
        paint.setShader(bgGradient);
        canvas.drawRect(0, 0, screenWidth, screenHeight, paint);
        paint.setShader(null);

        // ציור לוגו סנייק מעוצב
        float logoY = SNAKE_SIZE * 2.5f;
        paint.setColor(Color.parseColor("#00bcd4"));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(SNAKE_SIZE / 4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(screenWidth * 0.2f, logoY);
        path.quadTo(screenWidth * 0.4f, logoY - SNAKE_SIZE, screenWidth * 0.5f, logoY);
        path.quadTo(screenWidth * 0.6f, logoY + SNAKE_SIZE, screenWidth * 0.8f, logoY);
        canvas.drawPath(path, paint);
        
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setTextSize(SNAKE_SIZE * 1.5f);
        paint.setFakeBoldText(true);
        paint.setShadowLayer(5, 2, 2, Color.BLACK);
        String title = "SNAKE PRO";
        canvas.drawText(title, screenWidth / 2f - paint.measureText(title) / 2f, logoY + SNAKE_SIZE * 2.2f, paint);
        paint.clearShadowLayer();
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
                // מסגרת בחירה מעוגלת וזוהרת
                paint.setColor(Color.parseColor("#3399FF"));
                drawRoundRectCompat(canvas, SNAKE_SIZE * 0.5f, menuStartY - SNAKE_SIZE + (i * SNAKE_SIZE * 1.5f), 
                                  screenWidth - SNAKE_SIZE * 0.5f, menuStartY + SNAKE_SIZE * 0.5f + (i * SNAKE_SIZE * 1.5f), 15, paint);
                paint.setColor(Color.BLACK);
            } else {
                paint.setColor(Color.WHITE);
            }
            
            float xPos;
            if (currentLanguage == Language.HEBREW) {
                xPos = screenWidth - paint.measureText(items.get(i)) - SNAKE_SIZE * 1.5f;
            } else {
                xPos = SNAKE_SIZE * 1.5f;
            }
            canvas.drawText(items.get(i), xPos, menuStartY + (i * SNAKE_SIZE * 1.5f), paint);
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
            
            float xPos = (currentLanguage == Language.HEBREW) ? 
                         screenWidth - paint.measureText(maps[i].name()) - SNAKE_SIZE : 
                         SNAKE_SIZE;
            
            canvas.drawText(maps[i].name(), xPos, SNAKE_SIZE * 3.5f + (i * SNAKE_SIZE * 1.5f), paint);
        }
        drawNokiaFooter(canvas, t(R.string.btn_select_heb, R.string.btn_select_eng));
    }

    private void drawSpeedSelect(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.menu_speed_heb, R.string.menu_speed_eng));
        float barW = screenWidth * 0.8f; float segW = barW / 8;
        float sX = (screenWidth - barW) / 2f; float sY = screenHeight / 2f - SNAKE_SIZE;
        for (int i = 0; i < 8; i++) {
            paint.setColor(i <= speedLevel ? Color.GREEN : Color.parseColor("#003366"));
            canvas.drawRect(sX + (i * segW) + 2, sY, sX + ((i + 1) * segW) - 2, sY + SNAKE_SIZE * 2, paint);
        }
        drawNokiaFooter(canvas, t(R.string.btn_select_heb, R.string.btn_select_eng));
    }

    private void drawSettings(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.menu_settings_heb, R.string.menu_settings_eng));
        
        // סימון פריט נבחר בהגדרות
        paint.setColor(Color.parseColor("#3399FF"));
        float highlightY = (selectedMenuItem == 0) ? SNAKE_SIZE * 3.2f : SNAKE_SIZE * 5.2f;
        canvas.drawRect(0, highlightY, screenWidth, highlightY + SNAKE_SIZE * 1.5f, paint);

        paint.setTextSize(SNAKE_SIZE * 0.9f); 
        
        String langText = t(R.string.lang_label_heb, R.string.lang_label_eng);
        String vibStatus = vibrationEnabled ? t(R.string.on_heb, R.string.on_eng) : t(R.string.off_heb, R.string.off_eng);
        String vibText = t(R.string.vibration_label_heb, R.string.vibration_label_eng) + vibStatus;

        if (currentLanguage == Language.HEBREW) {
            paint.setColor(selectedMenuItem == 0 ? Color.BLACK : Color.WHITE);
            canvas.drawText(langText, screenWidth - paint.measureText(langText) - SNAKE_SIZE, SNAKE_SIZE * 4.2f, paint);
            paint.setColor(selectedMenuItem == 1 ? Color.BLACK : Color.WHITE);
            canvas.drawText(vibText, screenWidth - paint.measureText(vibText) - SNAKE_SIZE, SNAKE_SIZE * 6.2f, paint);
        } else {
            paint.setColor(selectedMenuItem == 0 ? Color.BLACK : Color.WHITE);
            canvas.drawText(langText, SNAKE_SIZE, SNAKE_SIZE * 4.2f, paint);
            paint.setColor(selectedMenuItem == 1 ? Color.BLACK : Color.WHITE);
            canvas.drawText(vibText, SNAKE_SIZE, SNAKE_SIZE * 6.2f, paint);
        }
        
        drawNokiaFooter(canvas, t(R.string.btn_change_heb, R.string.btn_change_eng));
    }

    private void drawGame(Canvas canvas) {
        // שטח המשחק מתחיל מתחת ל-Header (כ-2 שורות של SNAKE_SIZE)
        float gameStartY = SNAKE_SIZE * 2f;
        
        canvas.save();
        canvas.translate(0, gameStartY);

        // ציור רקע המשבצות (כמו בתמונה)
        for (int c = 0; c < screenWidth / SNAKE_SIZE; c++) {
            for (int r = 0; r < effectiveHeight / SNAKE_SIZE; r++) {
                // גיוון עדין בצבע הכחול של המשבצות למראה טקסטורלי
                if ((c + r) % 2 == 0) paint.setColor(Color.parseColor("#001a33"));
                else paint.setColor(Color.parseColor("#00152b"));
                canvas.drawRect(c * SNAKE_SIZE, r * SNAKE_SIZE, (c + 1) * SNAKE_SIZE, (r + 1) * SNAKE_SIZE, paint);
                
                // מסגרת עדינה לכל משבצת
                paint.setColor(Color.parseColor("#00264d"));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1);
                canvas.drawRect(c * SNAKE_SIZE, r * SNAKE_SIZE, (c + 1) * SNAKE_SIZE, (r + 1) * SNAKE_SIZE, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        // ציור מכשולים (כחול בולט כמו בתמונה)
        for (int[] wall : obstacles) {
            paint.setColor(Color.parseColor("#0066ff"));
            drawRoundRectCompat(canvas, wall[0] + 1, wall[1] + 1, wall[0] + SNAKE_SIZE - 1, wall[1] + SNAKE_SIZE - 1, 4, paint);
            paint.setColor(Color.parseColor("#004db3"));
            canvas.drawRect(wall[0] + 4, wall[1] + 4, wall[0] + SNAKE_SIZE - 4, wall[1] + 8, paint);
        }

        // ציור אוכל (תפוח אדום עם עלה כמו בתמונה)
        paint.setColor(Color.RED);
        drawRoundRectCompat(canvas, foodX + 4, foodY + 6, foodX + SNAKE_SIZE - 4, foodY + SNAKE_SIZE - 2, 12, paint);
        // העלה הירוק
        paint.setColor(Color.parseColor("#32CD32"));
        canvas.drawRect(foodX + SNAKE_SIZE/2f - 2, foodY + 2, foodX + SNAKE_SIZE/2f + 2, foodY + 8, paint);
        // ברק על התפוח
        paint.setColor(Color.WHITE);
        canvas.drawCircle(foodX + SNAKE_SIZE * 0.35f, foodY + SNAKE_SIZE * 0.45f, 2, paint);

        if (isSpecialFoodActive) {
            paint.setColor(Color.parseColor("#FFD700"));
            drawRoundRectCompat(canvas, specialFoodX + 2, specialFoodY + 2, specialFoodX + SNAKE_SIZE - 2, specialFoodY + SNAKE_SIZE - 2, 15, paint);
            
            // הילה זהובה חיצונית (זהב מנצנץ)
            long time = System.currentTimeMillis();
            float pulse = (float) Math.sin(time * 0.01) * 3;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setColor(Color.argb(180, 255, 215, 0));
            canvas.drawCircle(specialFoodX + SNAKE_SIZE/2f, specialFoodY + SNAKE_SIZE/2f, SNAKE_SIZE/2f + pulse + 2, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        
        // ציור הנחש (צהוב-כתום יוקרתי כמו בתמונה)
        for (int i = 0; i < snakeParts.size(); i++) {
            int[] p = snakeParts.get(i);
            if (i == 0) {
                // ראש עם פנים
                paint.setColor(Color.parseColor("#FFCC00"));
                drawRoundRectCompat(canvas, p[0] + 1, p[1] + 1, p[0] + SNAKE_SIZE - 1, p[1] + SNAKE_SIZE - 1, 8, paint);
                
                // עיניים ופה לכיוון הנסיעה
                paint.setColor(Color.BLACK);
                float eyeSize = SNAKE_SIZE * 0.12f;
                float frontOffset = SNAKE_SIZE * 0.25f;
                float sideOffset = SNAKE_SIZE * 0.25f;
                float mouthOffset = SNAKE_SIZE * 0.65f;
                
                float e1x, e1y, e2x, e2y, mx, my;
                float startAngle;

                if (currentDirection == 0) { // למעלה
                    e1x = p[0] + sideOffset; e1y = p[1] + frontOffset;
                    e2x = p[0] + SNAKE_SIZE - sideOffset; e2y = p[1] + frontOffset;
                    mx = p[0] + SNAKE_SIZE / 2f; my = p[1] + mouthOffset;
                    startAngle = 45;
                } else if (currentDirection == 1) { // למטה
                    e1x = p[0] + sideOffset; e1y = p[1] + SNAKE_SIZE - frontOffset;
                    e2x = p[0] + SNAKE_SIZE - sideOffset; e2y = p[1] + SNAKE_SIZE - frontOffset;
                    mx = p[0] + SNAKE_SIZE / 2f; my = p[1] + SNAKE_SIZE - mouthOffset;
                    startAngle = 225;
                } else if (currentDirection == 2) { // שמאלה
                    e1x = p[0] + frontOffset; e1y = p[1] + sideOffset;
                    e2x = p[0] + frontOffset; e2y = p[1] + SNAKE_SIZE - sideOffset;
                    mx = p[0] + mouthOffset; my = p[1] + SNAKE_SIZE / 2f;
                    startAngle = 315;
                } else { // ימינה (3)
                    e1x = p[0] + SNAKE_SIZE - frontOffset; e1y = p[1] + sideOffset;
                    e2x = p[0] + SNAKE_SIZE - frontOffset; e2y = p[1] + SNAKE_SIZE - sideOffset;
                    mx = p[0] + SNAKE_SIZE - mouthOffset; my = p[1] + SNAKE_SIZE / 2f;
                    startAngle = 135;
                }

                canvas.drawCircle(e1x, e1y, eyeSize, paint);
                canvas.drawCircle(e2x, e2y, eyeSize, paint);
                
                // חיוך קטן - תמיד מאחורי העיניים
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2);
                float mouthRadius = SNAKE_SIZE * 0.15f;
                android.graphics.RectF mouthRect = new android.graphics.RectF(mx - mouthRadius, my - mouthRadius, mx + mouthRadius, my + mouthRadius);
                canvas.drawArc(mouthRect, startAngle, 90, false, paint);
                paint.setStyle(Paint.Style.FILL);
            } else {
                // גוף צהוב עם הצללה עדינה
                paint.setColor(Color.parseColor(i % 2 == 0 ? "#FFD700" : "#FFC107"));
                drawRoundRectCompat(canvas, p[0] + 2, p[1] + 2, p[0] + SNAKE_SIZE - 2, p[1] + SNAKE_SIZE - 2, 6, paint);
            }
        }
        
        canvas.restore();

        // ניקוד (במרכז למעלה, גדול ולבן)
        paint.setColor(Color.WHITE);
        paint.setTextSize(SNAKE_SIZE * 2f);
        paint.setFakeBoldText(true);
        String scoreStr = "" + score;
        canvas.drawText(scoreStr, screenWidth/2f - paint.measureText(scoreStr)/2f, SNAKE_SIZE * 1.6f, paint);
        paint.setFakeBoldText(false);

        // סטופר לאוכל מיוחד (פס התקדמות מתחת לניקוד)
        if (isSpecialFoodActive) {
            float timerWidth = screenWidth * 0.5f;
            float timerX = (screenWidth - timerWidth) / 2f;
            float timerY = SNAKE_SIZE * 1.8f;
            
            paint.setColor(Color.argb(100, 0, 0, 0));
            canvas.drawRect(timerX, timerY, timerX + timerWidth, timerY + 8, paint);
            
            paint.setColor(Color.parseColor("#FFD700"));
            float currentProgress = (specialFoodTimer / 5000f) * timerWidth;
            canvas.drawRect(timerX, timerY, timerX + currentProgress, timerY + 8, paint);
            
            // טיימר ספרתי קטן
            paint.setTextSize(SNAKE_SIZE * 0.5f);
            paint.setColor(Color.WHITE);
            @SuppressLint("DefaultLocale") String timeStr = String.format("%.2f", specialFoodTimer / 1000.0);
            canvas.drawText(timeStr, timerX + timerWidth + 10, timerY + 7, paint);
        }

        // מסך השהיה (טקסט ורוד על רקע כחול שקוף כמו בתמונה)
        if (currentState == GameState.PAUSED) {
            paint.setColor(Color.argb(160, 0, 51, 102));
            canvas.drawRect(screenWidth * 0.1f, screenHeight/2f - SNAKE_SIZE * 2, screenWidth * 0.9f, screenHeight/2f + SNAKE_SIZE * 2, paint);
            
            paint.setColor(Color.parseColor("#FF6699")); // ורוד-אדמדם
            paint.setTextSize(SNAKE_SIZE * 1.5f);
            String txt = t(R.string.paused_heb, R.string.paused_eng);
            canvas.drawText(txt, screenWidth/2f - paint.measureText(txt)/2f, screenHeight/2f + SNAKE_SIZE * 0.5f, paint);
        }
    }

    private void drawHighScores(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.menu_high_score_heb, R.string.menu_high_score_eng));
        paint.setColor(Color.WHITE);
        
        if (highScores.isEmpty()) {
            paint.setTextSize(SNAKE_SIZE * 0.8f);
            String noScores = currentLanguage == Language.HEBREW ? "אין שיאים עדיין" : "No high scores yet";
            canvas.drawText(noScores, SNAKE_SIZE, SNAKE_SIZE * 5, paint);
        } else {
            paint.setTextSize(SNAKE_SIZE * 1.0f);
            float startY = SNAKE_SIZE * 4.5f;
            for (int i = 0; i < highScores.size(); i++) {
                String rank = (i + 1) + ". ";
                String scoreText = rank + highScores.get(i);
                
                // שימוש בגווני זהב/כסף/ארד ל-3 הראשונים
                if (i == 0) paint.setColor(Color.parseColor("#FFD700")); // זהב
                else if (i == 1) paint.setColor(Color.parseColor("#C0C0C0")); // כסף
                else if (i == 2) paint.setColor(Color.parseColor("#CD7F32")); // ארד
                else paint.setColor(Color.WHITE);
                
                float xPos = (currentLanguage == Language.HEBREW) ? 
                             screenWidth - paint.measureText(scoreText) - SNAKE_SIZE : 
                             SNAKE_SIZE;
                
                canvas.drawText(scoreText, xPos, startY + (i * SNAKE_SIZE * 1.3f), paint);
            }
        }
        drawNokiaFooter(canvas, null);
    }

    private void drawAbout(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.menu_about_heb, R.string.menu_about_eng));
        
        // רקע מעוצב לאודות
        paint.setColor(Color.argb(40, 255, 255, 255));
        drawRoundRectCompat(canvas, SNAKE_SIZE * 0.5f, SNAKE_SIZE * 2.5f, screenWidth - SNAKE_SIZE * 0.5f, screenHeight - SNAKE_SIZE * 3, 15, paint);

        paint.setColor(Color.WHITE);
        float startY = SNAKE_SIZE * 3.8f;
        float lineSpacing = SNAKE_SIZE * 1.1f;

        String[][] aboutLines = {
            { "SNAKE PRO - Nokia Edition", "סנייק פרו - מהדורת נוקיה" },
            { "Version 1.5.0", "גרסה 1.5.0" },
            { "----------------------", "----------------------" },
            { "Developed by:", "פותח על ידי:" },
            { "The Creator YT", "The Creator YT" },
            { "----------------------", "----------------------" },
            { "Classic arcade experience", "חוויית משחק קלאסית" },
            { "Optimized for Qin 1S+ & Jelly 2", "מותאם ל-Qin 1S+ ול-Jelly 2" }
        };

        for (int i = 0; i < aboutLines.length; i++) {
            String text = (currentLanguage == Language.HEBREW) ? aboutLines[i][1] : aboutLines[i][0];
            
            // עיצוב כותרות בתוך האודות
            if (i == 0 || i == 3) {
                paint.setFakeBoldText(true);
                paint.setTextSize(SNAKE_SIZE * 0.85f);
                paint.setColor(Color.parseColor("#00bcd4"));
            } else {
                paint.setFakeBoldText(false);
                paint.setTextSize(SNAKE_SIZE * 0.75f);
                paint.setColor(Color.WHITE);
            }

            float xPos = (currentLanguage == Language.HEBREW) ? 
                         screenWidth - paint.measureText(text) - SNAKE_SIZE * 1.2f : 
                         SNAKE_SIZE * 1.2f;
                         
            canvas.drawText(text, xPos, startY + (i * lineSpacing), paint);
        }
        
        paint.setFakeBoldText(false);
        drawNokiaFooter(canvas, null);
    }

    private void drawGameOver(Canvas canvas) {
        drawNokiaHeader(canvas, t(R.string.game_over_heb, R.string.game_over_eng));
        paint.setColor(Color.WHITE); paint.setTextSize(SNAKE_SIZE * 1.5f);
        canvas.drawText(t(R.string.score_label_heb, R.string.score_label_eng) + score, SNAKE_SIZE, screenHeight/2f, paint);
        
        // סימון כפתור נבחר ב-Game Over
        paint.setColor(Color.parseColor("#3399FF"));
        float btnX = (selectedMenuItem == 0) ? SNAKE_SIZE : screenWidth - SNAKE_SIZE * 5;
        canvas.drawRect(btnX - 5, screenHeight - SNAKE_SIZE * 2.2f, btnX + paint.measureText(t(R.string.btn_retry_heb, R.string.btn_retry_eng)) + 10, screenHeight - SNAKE_SIZE * 0.8f, paint);
        
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

    private void handleMenuSelection(int index) {
        int itemIndex = index;
        if (isResumable) {
            if (itemIndex == 0) { 
                currentState = GameState.PAUSED;
                return; 
            }
            itemIndex--;
        }
        
        if (itemIndex == 0) { resetGame(); currentState = GameState.PLAYING; }
        else if (itemIndex == 1) { currentState = GameState.MAP_SELECT; selectedMenuItem = currentMap.ordinal(); }
        else if (itemIndex == 2) { currentState = GameState.SPEED_SELECT; }
        else if (itemIndex == 3) { currentState = GameState.SETTINGS; selectedMenuItem = 0; }
        else if (itemIndex == 4) { currentState = GameState.HIGH_SCORES; }
        else if (itemIndex == 5) { currentState = GameState.ABOUT; }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(); float y = event.getY();
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchStartX = x; touchStartY = y; swipeTriggered = false;
            
            // בדיקת לחיצה על כפתור "חזור" - מיקום משתנה לפי השפה
            boolean isBackClicked;
            if (currentLanguage == Language.HEBREW) {
                isBackClicked = x < SNAKE_SIZE * 5 && y > screenHeight - SNAKE_SIZE * 3;
            } else {
                isBackClicked = x > screenWidth - SNAKE_SIZE * 5 && y > screenHeight - SNAKE_SIZE * 3;
            }

            if (currentState != GameState.PLAYING && isBackClicked) {
                if (currentState == GameState.MENU) {
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
                float menuYStart = SNAKE_SIZE * 6.0f; 
                selectedMenuItem = (int) ((y - menuYStart) / (SNAKE_SIZE * 1.5f));
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
                if (y > SNAKE_SIZE * 3 && y < SNAKE_SIZE * 5) { 
                    currentLanguage = (currentLanguage == Language.HEBREW) ? Language.ENGLISH : Language.HEBREW; 
                    prefs.edit().putInt("language", currentLanguage.ordinal()).apply(); 
                }
                else if (y > SNAKE_SIZE * 5 && y < SNAKE_SIZE * 7) { 
                    vibrationEnabled = !vibrationEnabled; 
                    prefs.edit().putBoolean("vibration_enabled", vibrationEnabled).apply(); 
                    if (vibrationEnabled) vibrator.vibrate(50); 
                }
            } else if (currentState == GameState.PAUSED) {
                currentState = GameState.PLAYING;
            } else if (currentState == GameState.GAME_OVER) {
                boolean isRetryClicked;
                if (currentLanguage == Language.HEBREW) {
                    isRetryClicked = x > screenWidth / 2f;
                } else {
                    isRetryClicked = x < screenWidth / 2f;
                }

                if (isRetryClicked) {
                    resetGame();
                    currentState = GameState.PLAYING;
                } else {
                    currentState = GameState.MENU;
                }
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (currentState == GameState.MENU && selectedMenuItem != -1) {
                handleMenuSelection(selectedMenuItem);
                vibrate(20);
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (currentState != GameState.PLAYING || swipeTriggered) return true;
            float dx = x - touchStartX; float dy = y - touchStartY;
            if (Math.abs(dx) > SNAKE_SIZE * 0.8f || Math.abs(dy) > SNAKE_SIZE * 0.8f) {
                int nD; if (Math.abs(dx) > Math.abs(dy)) nD = (dx > 0) ? 3 : 2; else nD = (dy > 0) ? 1 : 0;
                if (directionQueue.size() < 2) { directionQueue.add(nD); swipeTriggered = true; }
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // מיפוי כפתורי Soft Keys (שמאל וימין למעלה) וכפתור תפריט
        boolean isSoftLeft = (keyCode == KeyEvent.KEYCODE_SOFT_LEFT || keyCode == KeyEvent.KEYCODE_MENU);
        boolean isSoftRight = (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT || keyCode == KeyEvent.KEYCODE_BACK);
        boolean isActionKey = (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || isSoftLeft);

        if (isSoftRight) {
            if (currentState == GameState.PLAYING) {
                currentState = GameState.PAUSED;
                vibrate(20);
                return true;
            } else if (currentState == GameState.MENU) {
                return keyCode != KeyEvent.KEYCODE_BACK; // נותן למערכת לטפל ב-Back כדי לצאת מהאפליקציה
            } else {
                currentState = GameState.MENU;
                selectedMenuItem = 0;
                vibrate(20);
                return true;
            }
        }

        // ניווט בתפריטים באמצעות כפתורים
        if (currentState != GameState.PLAYING && currentState != GameState.PAUSED) {
            int itemCount;
            switch (currentState) {
                case MENU:
                    itemCount = (isResumable ? 7 : 6);
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        selectedMenuItem = (selectedMenuItem <= 0) ? itemCount - 1 : selectedMenuItem - 1;
                        vibrate(10);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        selectedMenuItem = (selectedMenuItem >= itemCount - 1) ? 0 : selectedMenuItem + 1;
                        vibrate(10);
                        return true;
                    } else if (isActionKey) {
                        handleMenuSelection(selectedMenuItem);
                        vibrate(20);
                        return true;
                    }
                    break;
                case MAP_SELECT:
                    itemCount = MapType.values().length;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        int nextMap = (currentMap.ordinal() == 0) ? itemCount - 1 : currentMap.ordinal() - 1;
                        currentMap = MapType.values()[nextMap];
                        prefs.edit().putInt("map_type", nextMap).apply();
                        vibrate(10);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        int nextMap = (currentMap.ordinal() >= itemCount - 1) ? 0 : currentMap.ordinal() + 1;
                        currentMap = MapType.values()[nextMap];
                        prefs.edit().putInt("map_type", nextMap).apply();
                        vibrate(10);
                        return true;
                    } else if (isActionKey) {
                        currentState = GameState.MENU;
                        vibrate(20);
                        return true;
                    }
                    break;
                case SPEED_SELECT:
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        speedLevel = Math.max(0, speedLevel - 1);
                        prefs.edit().putInt("speed_level", speedLevel).apply();
                        vibrate(10);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        speedLevel = Math.min(7, speedLevel + 1);
                        prefs.edit().putInt("speed_level", speedLevel).apply();
                        vibrate(10);
                        return true;
                    } else if (isActionKey) {
                        currentState = GameState.MENU;
                        vibrate(20);
                        return true;
                    }
                    break;
                case SETTINGS:
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        selectedMenuItem = (selectedMenuItem == 0) ? 1 : 0; 
                        vibrate(10);
                        return true;
                    } else if (isActionKey) {
                        if (selectedMenuItem == 0) {
                            currentLanguage = (currentLanguage == Language.HEBREW) ? Language.ENGLISH : Language.HEBREW;
                            prefs.edit().putInt("language", currentLanguage.ordinal()).apply();
                        } else {
                            vibrationEnabled = !vibrationEnabled;
                            prefs.edit().putBoolean("vibration_enabled", vibrationEnabled).apply();
                            if (vibrationEnabled) vibrate(50);
                        }
                        vibrate(20);
                        return true;
                    }
                    break;
                case GAME_OVER:
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        selectedMenuItem = (selectedMenuItem == 0) ? 1 : 0; 
                        vibrate(10);
                        return true;
                    } else if (isActionKey) {
                        if (selectedMenuItem == 0) { resetGame(); currentState = GameState.PLAYING; }
                        else currentState = GameState.MENU;
                        vibrate(20);
                        return true;
                    }
                    break;
                default:
                    if (isActionKey) {
                        currentState = GameState.MENU;
                        vibrate(20);
                        return true;
                    }
            }
        }

        if (currentState == GameState.PLAYING) {
            int nD;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_2: nD = 0; break; 
                case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_8: nD = 1; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_4: nD = 2; break; 
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_6: nD = 3; break;
                case KeyEvent.KEYCODE_SOFT_LEFT: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_P: case KeyEvent.KEYCODE_5:
                    currentState = GameState.PAUSED; 
                    vibrate(20);
                    return true;
                default: return super.onKeyDown(keyCode, event);
            }
            if (directionQueue.size() < 2) { directionQueue.add(nD); }
            return true;
        }

        if (currentState == GameState.PAUSED && isActionKey) {
            currentState = GameState.PLAYING;
            vibrate(20);
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
