package com.cyber.max;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import io.noties.markwon.Markwon;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView, rvHistory;
    private EditText etInput;
    private TextView tvStatus;
    private GeckoView geckoView;
    private LinearLayout nativeLayout;
    private MaterialToolbar toolbar;
    private DrawerLayout drawerLayout;
    private GeckoSession session;
    private static GeckoRuntime runtime;

    private boolean isRunning = false;
    private boolean isPageHooked = false;

    private MsgAdapter adapter;
    private HistoryAdapter historyAdapter;
    private List<Msg> msgList = new ArrayList<>();
    private List<ChatSession> historyList = new ArrayList<>();
    private ChatSession currentSession;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder sseBuffer = new StringBuilder();
    private final StringBuilder pendingAiText = new StringBuilder();
    private boolean isUiUpdatePending = false;
    
    private SharedPreferences sp;
    private Markwon markwon;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences("cyber_chat", Context.MODE_PRIVATE);
        markwon = Markwon.create(this);
        loadHistory();

        drawerLayout = findViewById(R.id.drawerLayout);
        toolbar = findViewById(R.id.toolbar);
        recyclerView = findViewById(R.id.recyclerView);
        etInput = findViewById(R.id.etInput);
        ImageButton btnSend = findViewById(R.id.btnSend);
        geckoView = findViewById(R.id.geckoView);
        nativeLayout = findViewById(R.id.nativeLayout);
        tvStatus = findViewById(R.id.tvStatus);
        rvHistory = findViewById(R.id.rvHistory);

        // 初始化 RecyclerView
        adapter = new MsgAdapter();
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recyclerView.setLayoutManager(lm);
        recyclerView.setAdapter(adapter);

        historyAdapter = new HistoryAdapter();
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        rvHistory.setAdapter(historyAdapter);

        // 大厂交互：点击菜单弹出抽屉，长按标题切换网页/原生
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(rvHistory));
        
        toolbar.setOnLongClickListener(v -> {
            if (geckoView.getVisibility() == View.VISIBLE) {
                geckoView.setVisibility(View.GONE);
                nativeLayout.setVisibility(View.VISIBLE);
                Toast.makeText(this, "已切换到原生聊天", Toast.LENGTH_SHORT).show();
            } else {
                geckoView.setVisibility(View.VISIBLE);
                nativeLayout.setVisibility(View.GONE);
                Toast.makeText(this, "请刷新或重新登录", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        // 新建对话
        findViewById(R.id.btnNewChat).setOnClickListener(v -> {
            saveCurrentSession();
            msgList.clear();
            currentSession = new ChatSession("新对话 " + (historyList.size() + 1), new ArrayList<>());
            historyList.add(0, currentSession);
            historyAdapter.notifyDataSetChanged();
            adapter.notifyDataSetChanged();
            drawerLayout.closeDrawers();
            if (isPageHooked) session.evaluateJS("javascript:location.reload();", null);
            Toast.makeText(this, "已开启新对话", Toast.LENGTH_SHORT).show();
        });

        initGeckoView();
        btnSend.setOnClickListener(v -> sendMessage());
    }

    private void sendMessage() {
        String prompt = etInput.getText().toString().trim();
        if (TextUtils.isEmpty(prompt) || isRunning || !isPageHooked) return;
        etInput.setText("");
        Msg userMsg = new Msg("User", prompt);
        msgList.add(userMsg);
        adapter.notifyItemInserted(msgList.size() - 1);
        recyclerView.scrollToPosition(msgList.size() - 1);
        
        isRunning = true;
        toolbar.setSubtitle("正在思考...");
        sseBuffer.setLength(0);
        pendingAiText.setLength(0);

        String js = "javascript:window.cyberSend(" + JSONObject.quote(prompt) + ");";
        session.evaluateJS(js, null);
    }

    private void initGeckoView() {
        if (runtime == null) runtime = GeckoRuntime.create(this);
        session = new GeckoSession();

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(@NonNull GeckoSession s, String title) {
                if (title == null) return;
                
                if (title.contains("DeepSeek") && !isPageHooked) {
                    injectHookScript();
                } else if (title.equals("就绪") && !isPageHooked) {
                    isPageHooked = true;
                    runOnUiThread(() -> toolbar.setSubtitle("就绪"));
                } else if (title.startsWith("回复:")) {
                    String text = title.substring(3);
                    runOnUiThread(() -> {
                        if ("结束".equals(text)) {
                            flushUiUpdate();
                            isRunning = false;
                            toolbar.setSubtitle("就绪");
                            saveCurrentSession();
                        } else {
                            pendingAiText.append(text);
                            scheduleUiUpdate();
                        }
                    });
                }
            }
        });

        session.open(runtime);
        geckoView.setSession(session);
        session.loadUri("https://chat.deepseek.com");
    }

    private void injectHookScript() {
        String js = 
            "(function() {" +
            "  if (window.cyberHooked) return;" +
            "  window.cyberHooked = true;" +
            "  window.cyberSend = function(text) {" +
            "    let ta = document.querySelector('textarea');" +
            "    if (!ta) return;" +
            "    let setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;" +
            "    setter.call(ta, text);" +
            "    ta.dispatchEvent(new Event('input', { bubbles: true }));" +
            "    setTimeout(function() {" +
            "      ta.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));" +
            "    }, 300);" +
            "  };" +
            "  let origFetch = window.fetch;" +
            "  window.fetch = async function() {" +
            "    let res = await origFetch.apply(this, arguments);" +
            "    let url = arguments[0];" +
            "    if (typeof url === 'string' && url.includes('/chat/completion')) {" +
            "      let clone = res.clone();" +
            "      let reader = clone.body.getReader();" +
            "      let dec = new TextDecoder();" +
            "      let go = () => reader.read().then(({done, value}) => {" +
            "        if (done) { document.title = '回复:结束'; return; }" +
            "        let txt = dec.decode(value, {stream:true});" +
            "        let lines = txt.split('\\n');" +
            "        for(let l of lines) {" +
            "          if(l.startsWith('data: ')) {" +
            "            try {" +
            "              let json = JSON.parse(l.substring(6));" +
            "              if(json.v) document.title = '回复:' + json.v;" +
            "            } catch(e) {}" +
            "          }" +
            "        }" +
            "        go();" +
            "      }); go();" +
            "    }" +
            "    return res;" +
            "  };" +
            "  document.title = '就绪';" +
            "})();";
        session.evaluateJS("javascript:" + js, null);
    }

    private void scheduleUiUpdate() {
        if (!isUiUpdatePending) {
            isUiUpdatePending = true;
            mainHandler.postDelayed(this::flushUiUpdate, 50);
        }
    }

    private void flushUiUpdate() {
        if (pendingAiText.length() > 0) {
            String newText = pendingAiText.toString();
            pendingAiText.setLength(0);
            appendAiMsg(newText);
        }
        isUiUpdatePending = false;
    }

    private void appendAiMsg(String text) {
        if (msgList.isEmpty() || !"AI".equals(msgList.get(msgList.size() - 1).role)) {
            msgList.add(new Msg("AI", text));
            adapter.notifyItemInserted(msgList.size() - 1);
        } else {
            Msg last = msgList.get(msgList.size() - 1);
            last.content += text;
            adapter.notifyItemChanged(msgList.size() - 1);
        }
        recyclerView.scrollToPosition(msgList.size() - 1);
    }

    // 持久化存储
    private void saveCurrentSession() {
        if (currentSession == null) {
            currentSession = new ChatSession("新对话", new ArrayList<>());
            historyList.add(0, currentSession);
        }
        currentSession.messages = msgList;
        Gson gson = new Gson();
        sp.edit().putString("history", gson.toJson(historyList)).apply();
    }

    private void loadHistory() {
        String json = sp.getString("history", null);
        if (json != null) {
            Type type = new TypeToken<List<ChatSession>>(){}.getType();
            historyList = new Gson().fromJson(json, type);
            if (historyList != null && !historyList.isEmpty()) {
                currentSession = historyList.get(0);
                msgList.addAll(currentSession.messages);
            }
        }
    }

    class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            Msg msg = msgList.get(position);
            // 使用 Markwon 渲染 Markdown
            markwon.setMarkdown(holder.tvContent, msg.content);
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if ("User".equals(msg.role)) {
                params.gravity = android.view.Gravity.END;
                holder.tvContent.setBackgroundResource(R.drawable.bg_user_bubble);
                holder.tvContent.setTextColor(0xFFFFFFFF);
            } else {
                params.gravity = android.view.Gravity.START;
                holder.tvContent.setBackgroundResource(R.drawable.bg_ai_bubble);
                holder.tvContent.setTextColor(0xFF00E5FF);
            }
            holder.tvContent.setLayoutParams(params);
        }
        @Override public int getItemCount() { return msgList.size(); }
        class VH extends RecyclerView.ViewHolder { TextView tvContent; VH(View v) { super(v); tvContent = v.findViewById(R.id.tvContent); } }
    }

    class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            ChatSession cs = historyList.get(position);
            holder.tvTitle.setText(cs.title);
            holder.itemView.setOnClickListener(v -> {
                saveCurrentSession();
                currentSession = cs;
                msgList.clear();
                msgList.addAll(cs.messages);
                adapter.notifyDataSetChanged();
                drawerLayout.closeDrawers();
            });
        }
        @Override public int getItemCount() { return historyList.size(); }
        class VH extends RecyclerView.ViewHolder { TextView tvTitle; VH(View v) { super(v); tvTitle = v.findViewById(R.id.tvTitle); } }
    }

    static class Msg { String role, content; Msg(String role, String content) { this.role = role; this.content = content; } }
    static class ChatSession { String title; List<Msg> messages; ChatSession(String title, List<Msg> messages) { this.title = title; this.messages = messages; } }
}
