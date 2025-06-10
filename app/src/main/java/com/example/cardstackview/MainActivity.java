package com.example.cardstackview;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.drawerlayout.widget.DrawerLayout;

import com.example.cardstackview.databinding.ActivityMainBinding;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;
import com.yuyakaido.android.cardstackview.CardStackLayoutManager;
import com.yuyakaido.android.cardstackview.CardStackListener;
import com.yuyakaido.android.cardstackview.Direction;
import com.yuyakaido.android.cardstackview.SwipeAnimationSetting;
import com.yuyakaido.android.cardstackview.StackFrom;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    private MaterialToolbar idTopAppBar;
    private DrawerLayout idDrawer;
    private NavigationView idNavView;
    private ActivityMainBinding binding;
    private CardStackLayoutManager layoutManager;
    private CardAdapter adapter;
    private List<Vagas> vagasList = new ArrayList<>();
    private List<Vagas> allVagas = new ArrayList<>();
    private ProgressBar progressBar;
    private int currentPosition = 0;
    private static final int BATCH_SIZE = 10;
    private boolean isLoading = false;
    private NavigationView navigationView;
    private boolean isSwiping = false;
    private long lastSwipeTime = 0;
    private static final long MIN_SWIPE_INTERVAL = 500; // 0.5 segundos entre swipes

    private static final int CODE_GET_REQUEST = 1024;
    private static final int CODE_POST_REQUEST = 1025;
    private static final int REQUEST_CODE_FAVORITOS = 100;

    private ExecutorService executor;
    private VagaDatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        setContentView(binding.getRoot());

        // Inicializa o executor
        executor = AppExecutor.getExecutor();

        dbHelper = new VagaDatabaseHelper(this);
        progressBar = findViewById(R.id.progressBar);
        adapter = new CardAdapter(this, vagasList);

        setupCardStackView();
        setupNavigation();
        buscarVagas();

        SharedPreferences prefs = getSharedPreferences("user_prefs", MODE_PRIVATE);
        String userType = prefs.getString("user_type", "Física"); // Default: candidato
        boolean isEmpresa = userType.equalsIgnoreCase("Jurídica");

        // Filtra itens do menu de acordo com o tipo de usuário
        if (idNavView != null) {
            if (isEmpresa) {
                // Empresa: mostra opções de empresa, esconde as de candidato
                idNavView.getMenu().findItem(R.id.idVagasItemMenu).setVisible(false);
                idNavView.getMenu().findItem(R.id.idLoginItemMenu).setVisible(false);
            } else {
                // Candidato: mostra opções de candidato, esconde as de empresa
                idNavView.getMenu().findItem(R.id.idCriarVagasItemMenu).setVisible(false);
                idNavView.getMenu().findItem(R.id.idLoginItemMenu).setVisible(false);
                // Outras opções específicas de empresa, caso existam, devem ser escondidas aqui também
            }
        }
    }

    private void setupCardStackView() {
        CardStackListener cardStackListener = new CardStackListener() {
            @Override
            public void onCardSwiped(Direction direction) {
                long now = System.currentTimeMillis();
                if (isSwiping || now - lastSwipeTime < MIN_SWIPE_INTERVAL ||
                        layoutManager.getTopPosition() >= vagasList.size()) {
                    return;
                }

                isSwiping = true;
                lastSwipeTime = now;

                int pos = layoutManager.getTopPosition() - 1;
                if (pos >= 0 && pos < vagasList.size()) {
                    Vagas vaga = vagasList.get(pos);

                    AppExecutor.getExecutor().execute(() -> {
                        if (direction == Direction.Right) {
                            processarLike(vaga);
                        } else if (direction == Direction.Left) {
                            registrarInteresse(vaga); // Adicione esta linha para registrar rejeição
                        }

                        runOnUiThread(() -> {
                            if (pos >= vagasList.size() - 3 && currentPosition < allVagas.size()) {
                                loadNextBatch();
                            }
                            isSwiping = false;
                        });
                    });
                } else {
                    isSwiping = false;
                }
            }

            @Override
            public void onCardDragging(Direction direction, float ratio) {
                if (direction == Direction.Bottom && ratio > 0.5f) {
                    binding.cardStack.rewind();
                    binding.cardStack.swipe();
                }
            }

            @Override
            public void onCardRewound() {
            }

            @Override
            public void onCardCanceled() {
            }

            @Override
            public void onCardAppeared(View view, int position) {
            }

            @Override
            public void onCardDisappeared(View view, int position) {
            }
        };

        layoutManager = new CardStackLayoutManager(this, cardStackListener);
        layoutManager.setStackFrom(StackFrom.None);
        layoutManager.setVisibleCount(3);
        layoutManager.setDirections(Arrays.asList(Direction.Left, Direction.Right, Direction.Bottom));

        binding.cardStack.setLayoutManager(layoutManager);
        binding.cardStack.setAdapter(adapter);

        ImageButton btnReject = findViewById(R.id.btnReject);
        ImageButton btnLike = findViewById(R.id.btnLike);
        ImageButton btnDown = findViewById(R.id.btnDown);

        btnLike.setOnClickListener(v -> {
            if (canSwipe()) {
                // Feedback visual
                v.animate().scaleX(0.8f).scaleY(0.8f).setDuration(100)
                        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(100));
                isSwiping = true;
                lastSwipeTime = System.currentTimeMillis();

                int pos = layoutManager.getTopPosition();
                if (pos < vagasList.size()) {
                    Vagas vaga = vagasList.get(pos);
                    processarLike(vaga); // Processa o like antes do swipe
                }

                SwipeAnimationSetting setting = new SwipeAnimationSetting.Builder()
                        .setDirection(Direction.Right)
                        .setDuration(400)
                        .build();
                layoutManager.setSwipeAnimationSetting(setting);
                binding.cardStack.swipe();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    isSwiping = false;
                }, 500);
            }
        });

        btnReject.setOnClickListener(v -> {
            if (canSwipe()) {
                isSwiping = true;
                lastSwipeTime = System.currentTimeMillis();

                SwipeAnimationSetting setting = new SwipeAnimationSetting.Builder()
                        .setDirection(Direction.Left)
                        .setDuration(400)
                        .build();
                layoutManager.setSwipeAnimationSetting(setting);
                binding.cardStack.swipe();

                int pos = layoutManager.getTopPosition();
                if (pos < vagasList.size()) {
                    Vagas vaga = vagasList.get(pos);
                    registrarInteresse(vaga); // Registra o interesse (rejeição)
                }

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    isSwiping = false;
                }, 500);
            }
        });

        btnDown.setOnClickListener(v -> {
            if (canSwipe()) {
                isSwiping = true;
                lastSwipeTime = System.currentTimeMillis();

                SwipeAnimationSetting setting = new SwipeAnimationSetting.Builder()
                        .setDirection(Direction.Bottom)
                        .setDuration(400)
                        .build();
                layoutManager.setSwipeAnimationSetting(setting);
                binding.cardStack.swipe();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    isSwiping = false;
                    // Para o botão de baixo, não processamos nada adicional
                }, 500);
            }
        });
    }

    private boolean canSwipe() {
        long now = System.currentTimeMillis();
        return !isSwiping &&
                now - lastSwipeTime >= MIN_SWIPE_INTERVAL &&
                !vagasList.isEmpty() &&
                layoutManager.getTopPosition() < vagasList.size();
    }

    private void processarLike(Vagas vaga) {
        if (vaga == null) return;

        runOnUiThread(() -> {
            if (!dbHelper.isVagaFavorita(vaga.getVaga_id())) {
                boolean salvou = dbHelper.adicionarVagaFavorita(vaga);
                if (salvou) {
                    Toast.makeText(MainActivity.this, "Vaga favoritada!", Toast.LENGTH_SHORT).show();
                    Log.d("FAVORITOS", "Vaga " + vaga.getVaga_id() + " salva nos favoritos");

                    // Remove da lista atual
                    int index = vagasList.indexOf(vaga);
                    if (index != -1) {
                        vagasList.remove(index);
                        adapter.notifyItemRemoved(index);
                    }

                    // Carrega mais vagas se necessário
                    if (vagasList.size() < 3 && currentPosition < allVagas.size()) {
                        loadNextBatch();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Erro ao favoritar vaga", Toast.LENGTH_SHORT).show();
                }
            }
            registrarInteresse(vaga);
        });
    }

    private void loadNextBatch() {
        if (isLoading || isSwiping || currentPosition >= allVagas.size()) return;

        isLoading = true;
        binding.progressBar.setVisibility(View.VISIBLE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            List<Integer> idsFavoritos = dbHelper.getIdsVagasFavoritas();

            List<Vagas> batch = new ArrayList<>();
            while (currentPosition < allVagas.size() && batch.size() < BATCH_SIZE) {
                Vagas vaga = allVagas.get(currentPosition);
                if (!idsFavoritos.contains(vaga.getVaga_id())) {
                    batch.add(vaga);
                }
                currentPosition++;
            }

            if (!batch.isEmpty()) {
                int startPosition = vagasList.size();
                vagasList.addAll(batch);
                adapter.notifyItemRangeInserted(startPosition, batch.size());
            } else if (currentPosition >= allVagas.size()) {
                Toast.makeText(MainActivity.this, "Nenhuma vaga nova para mostrar", Toast.LENGTH_SHORT).show();
            }

            isLoading = false;
            binding.progressBar.setVisibility(View.GONE);
            preloadImages(batch);
        }, 150);
    }

    private void preloadImages(List<Vagas> batch) {
        try {
            AppExecutor.getExecutor().execute(() -> {
                // Implemente o pré-carregamento de imagens aqui se necessário
                for (Vagas vaga : batch) {
                    // Simulação de pré-carregamento
                    Log.d("Preload", "Pré-carregando dados para vaga: " + vaga.getVaga_id());
                }
            });
        } catch (RejectedExecutionException e) {
            Log.e("MainActivity", "Erro ao executar pré-carregamento", e);
        }
    }

    private void buscarVagas() {
        new PerformNetworkRequest(Api.URL_GET_VAGAS, null, CODE_GET_REQUEST).execute();
    }

    private void registrarInteresse(Vagas vaga) {
        AppExecutor.getExecutor().execute(() -> {
            HashMap<String, String> params = new HashMap<>();
            params.put("vaga_id", String.valueOf(vaga.getVaga_id()));
            params.put("usuario_id", "1");

            try {
                String resultado = new RequestHandler().sendPostRequest(Api.URL_REGISTRAR_INTERESSE, params);
                JSONObject response = new JSONObject(resultado);
                if (response.getBoolean("error")) {
                    Log.e("API", "Erro ao registrar interesse: " + response.getString("message"));
                }
            } catch (Exception e) {
                Log.e("API", "Erro ao registrar interesse", e);
            }
        });
    }

    private class PerformNetworkRequest extends AsyncTask<Void, Void, String> {
        String url;
        HashMap<String, String> params;
        int requestCode;

        PerformNetworkRequest(String url, HashMap<String, String> params, int requestCode) {
            this.url = url;
            this.params = params;
            this.requestCode = requestCode;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressBar.setVisibility(View.VISIBLE);
            binding.cardStack.setVisibility(View.GONE);
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            progressBar.setVisibility(View.GONE);
            binding.cardStack.setVisibility(View.VISIBLE);

            try {
                JSONObject response = new JSONObject(s);
                if (!response.getBoolean("error")) {
                    if (response.has("vagas")) {
                        JSONArray vagasArray = response.getJSONArray("vagas");

                        allVagas.clear();
                        vagasList.clear();
                        currentPosition = 0;

                        for (int i = 0; i < vagasArray.length(); i++) {
                            JSONObject vagaJson = vagasArray.getJSONObject(i);

                            String beneficios = vagaJson.optString("beneficios_vagas", "Não informado");
                            if (beneficios == null || beneficios.equals("null") || beneficios.trim().isEmpty()) {
                                beneficios = "Não informado";
                            }

                            Vagas vaga = new Vagas();
                            vaga.setVaga_id(vagaJson.optInt("id_vagas"));
                            vaga.setTitulo(vagaJson.optString("titulo_vagas", "Não informado"));
                            vaga.setDescricao(vagaJson.optString("descricao_vagas", "Não informado"));
                            vaga.setLocalizacao(vagaJson.optString("local_vagas", "Não informado"));
                            vaga.setSalario(vagaJson.optString("salario_vagas", "Não informado"));
                            vaga.setRequisitos(vagaJson.optString("requisitos_vagas", "Não informado"));
                            vaga.setNivel_experiencia(vagaJson.optString("nivel_experiencia", "Não informado"));
                            vaga.setTipo_contrato(vagaJson.optString("tipo_contrato", "Não informado"));
                            vaga.setArea_atuacao(vagaJson.optString("area_atuacao", "Não informado"));
                            vaga.setBeneficios(beneficios);
                            vaga.setVinculo(vagaJson.optString("vinculo_vagas", "Não informado"));
                            vaga.setRamo(vagaJson.optString("ramo_vagas", "Não informado"));
                            vaga.setEmpresa_id(vagaJson.optInt("id_empresa"));
                            vaga.setNome_empresa(vagaJson.optString("nome_empresa", "Empresa não informada"));
                            vaga.setHabilidadesDesejaveisStr(null);
                            vaga.setId_usuario(0); // Set default value or get from JSON if available


                            allVagas.add(vaga);
                        }

                        List<Integer> idsFavoritos = dbHelper.getIdsVagasFavoritas();
                        List<Vagas> vagasNaoFavoritadas = new ArrayList<>();
                        for (Vagas vaga : allVagas) {
                            if (!idsFavoritos.contains(vaga.getVaga_id())) {
                                vagasNaoFavoritadas.add(vaga);
                            }
                        }
                        allVagas = vagasNaoFavoritadas;

                        if (!allVagas.isEmpty()) {
                            loadNextBatch();
                        } else {
                            Toast.makeText(MainActivity.this, "Nenhuma vaga nova para mostrar", Toast.LENGTH_SHORT).show();
                        }
                    }
                } else {
                    Toast.makeText(MainActivity.this, response.getString("message"), Toast.LENGTH_SHORT).show();
                }
            } catch (JSONException e) {
                e.printStackTrace();
                Toast.makeText(MainActivity.this, "Erro ao processar dados: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        @Override
        protected String doInBackground(Void... voids) {
            RequestHandler requestHandler = new RequestHandler();
            try {
                if (requestCode == CODE_POST_REQUEST) {
                    return requestHandler.sendPostRequest(url, params);
                } else if (requestCode == CODE_GET_REQUEST) {
                    return requestHandler.sendGetRequest(url);
                }
            } catch (Exception e) {
                return "{\"error\":true,\"message\":\"" + e.getMessage() + "\"}";
            }
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_FAVORITOS) {
            allVagas.clear();
            vagasList.clear();
            currentPosition = 0;
            buscarVagas();
        }
    }

    private void setupNavigation() {
        idTopAppBar = findViewById(R.id.idMainTopAppBar);
        setSupportActionBar(idTopAppBar);

        idDrawer = findViewById(R.id.idDrawer);
        idNavView = findViewById(R.id.idNavView);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, idDrawer, idTopAppBar,
                R.string.open_drawer, R.string.close_drawer
        );
        idDrawer.addDrawerListener(toggle);
        toggle.syncState();

        idNavView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.idLoginItemMenu) {
                startActivity(new Intent(this, LoginActivity.class));
                finish();
            } else if (id == R.id.idVagasItemMenu) {
                Toast.makeText(this, "Já está em Vagas", Toast.LENGTH_SHORT).show();
            } else if (id == R.id.idConfigItemMenu) {
                startActivity(new Intent(this, ConfigActivity.class));
            } else if (id == R.id.idAjudaItemMenu) {
                startActivity(new Intent(this, FeedbackActivity.class));
            } else if (id == R.id.idSobreItemMenu) {
                startActivity(new Intent(this, SobreNosActivity.class));
            } else if (id == R.id.idCriarVagasItemMenu) {
                startActivity(new Intent(this, CriarVagaActivity.class));
            }

            idDrawer.closeDrawers();
            return true;
        });

        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();

            if (itemId == R.id.nav_profile) {
                startActivity(new Intent(this, PerfilActivity.class));
                return true;
            } else if (itemId == R.id.nav_favorite) {
                startActivityForResult(new Intent(this, FavoritosActivity.class), REQUEST_CODE_FAVORITOS);
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Não desligamos o executor aqui porque é compartilhado (singleton)
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    public static class AppExecutor {
        private static ExecutorService instance;

        public static synchronized ExecutorService getExecutor() {
            if (instance == null || instance.isShutdown() || instance.isTerminated()) {
                instance = Executors.newFixedThreadPool(2);
            }
            return instance;
        }

        public static void shutdown() {
            if (instance != null) {
                instance.shutdownNow();
                instance = null;
            }
        }
    }
}