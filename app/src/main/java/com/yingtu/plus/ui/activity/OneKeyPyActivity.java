package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.yingtu.plus.R;
import com.yingtu.plus.api.OneKeyPyService;
import com.yingtu.plus.base.BaseActivity;

/**
 * 一键写 PY 页（从修仙版移植：设置页 →「一键写PY」，两个输入框 +「生成并导入」按钮）。
 *
 * 输入网址（必填）、站名（可选），点生成后走 OneKeyPyService：域名精确匹配
 * 只显示“已匹配模板”，探测与模板填充全在工具 py 里；生成好先校验再导入，
 * 同名站就地更新（换域名重生成）。
 * 成功卡片可跳「站源管理」查看。注意：这里只做探测命中与语法校验，不验真播。
 */
public class OneKeyPyActivity extends BaseActivity {

    private EditText etUrl, etName;
    private TextView tvTemplate, tvResult;
    private Button btnGenerate, btnOpenSources;
    private View progress;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_onekey_py;
    }

    @Override
    protected void init() {
        find(R.id.ivBack).setOnClickListener(v -> finish());
        etUrl = find(R.id.etUrl);
        etName = find(R.id.etName);
        tvTemplate = find(R.id.tvTemplate);
        tvResult = find(R.id.tvResult);
        btnGenerate = find(R.id.btnGenerate);
        btnOpenSources = find(R.id.btnOpenSources);
        progress = find(R.id.progress);

        etUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                showTemplateHint(s.toString());
            }
        });
        btnGenerate.setOnClickListener(v -> startGenerate());
        btnOpenSources.setOnClickListener(v -> startActivity(
                new Intent(this, SourceActivity.class)));
    }

    /** 输完网址即显示匹配到的模板（只当提示，不决定生成路子）。 */
    private void showTemplateHint(String url) {
        if (TextUtils.isEmpty(url.trim())) {
            tvTemplate.setText("");
            return;
        }
        String template = OneKeyPyService.get().matchDomainTemplate(this, url);
        tvTemplate.setText(template == null
                ? "未匹配已知模板，走通用探测"
                : "已匹配模板：" + template);
    }

    private void startGenerate() {
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            toast("请先输入网站地址");
            return;
        }
        setGenerating(true);
        tvResult.setVisibility(View.GONE);
        btnOpenSources.setVisibility(View.GONE);
        OneKeyPyService.get().generate(this, url, etName.getText().toString(),
                result -> {
                    setGenerating(false);
                    if (!result.ok) {
                        tvResult.setVisibility(View.VISIBLE);
                        tvResult.setText("生成失败：" + result.message);
                        toast(result.message);
                        return;
                    }
                    String text = result.message + "\n站名：" + result.siteName
                            + "\n模式：" + result.mode
                            + (result.templateId.isEmpty() ? ""
                            : "\n模板：" + result.templateId)
                            + (result.api.isEmpty() ? "" : "\n接口：" + result.api)
                            + "\n（只做了语法校验，未验真播）";
                    tvResult.setVisibility(View.VISIBLE);
                    tvResult.setText(text);
                    btnOpenSources.setVisibility(View.VISIBLE);
                    toast(result.message);
                });
    }

    private void setGenerating(boolean generating) {
        btnGenerate.setEnabled(!generating);
        btnGenerate.setText(generating ? "生成中…" : "生成并导入");
        progress.setVisibility(generating ? View.VISIBLE : View.GONE);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
