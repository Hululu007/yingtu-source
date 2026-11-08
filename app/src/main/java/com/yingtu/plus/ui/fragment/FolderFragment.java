package com.yingtu.plus.ui.fragment;

import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.Fragment;

import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.bean.Class;
import com.yingtu.plus.bean.Filter;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.bean.Value;

import java.util.HashMap;

/**
 * 文件夹分类内容页：宿在 FolderActivity 里，承接一个分类的筛选与下钻。
 * 结构照 webhtv mobile 版 FolderFragment 自己重写，基类用树里现有的
 * BaseLazyFragment（树里没有 webhtv 那种 ViewBinding 版 BaseFragment）。
 *
 * 待补：内容子页 TypeFragment 树里还没有（另见 init 里的说明），
 * 目前只建壳：标题与返回栈逻辑是全的，分类视频列表等 TypeFragment
 * 移植进来后在 init 里挂到 R.id.container 上。
 */
public class FolderFragment extends BaseLazyFragment {

    private Class mType;

    public static FolderFragment newInstance(String key, Class type, int y) {
        Bundle args = new Bundle();
        args.putInt("y", y);
        args.putString("key", key);
        args.putParcelable("type", type);
        FolderFragment fragment = new FolderFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    public Class getType() {
        return getArguments().getParcelable("type");
    }

    private int getY() {
        return getArguments().getInt("y");
    }

    private Fragment getChild() {
        return getChildFragmentManager().findFragmentById(R.id.container);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.fragment_folder;
    }

    @Override
    protected void init(View root) {
        mType = getType();
        // TypeFragment 未移植：树里没有 TypeFragment/VodAdapter/CustomScroller，
        // 这里先不挂内容子页，只把分类与初始筛选备好，等 TypeFragment 落地后
        // 在此执行 replace(R.id.container, TypeFragment.newInstance(
        // getKey(), mType.getTypeId(), mType.getStyle(), getExtend(),
        // mType.isFolder(), getY()))。
    }

    private HashMap<String, String> getExtend() {
        HashMap<String, String> extend = new HashMap<>();
        for (Filter filter : mType.getFilters()) {
            if (filter.getInit() != null) extend.put(filter.getKey(), filter.setSelected(filter.getInit()));
        }
        return extend;
    }

    /**
     * 子分类下钻入口：由内容子页点文件夹分类时回调。
     * 待 TypeFragment 移植后实现（建下一级 TypeFragment、隐藏当前页、入返回栈）。
     */
    public void openFolder(String typeId, HashMap<String, String> extend) {
    }

    /**
     * webhtv 里取父级 VodFragment 的 Result；树里没有 VodFragment，
     * 且 FolderActivity 宿主下本来就没有父级结果页，暂返回空。
     */
    public Result getResult() {
        return null;
    }

    /** 待 TypeFragment 移植后转发给内容子页。 */
    public void onRefresh() {
    }

    /** 待 TypeFragment 移植后转发给内容子页。 */
    public void scrollToTop() {
    }

    /** 待 TypeFragment 移植后转发给内容子页。 */
    public void setFilter(String key, Value value) {
    }

    public boolean canBack() {
        return getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    public void goBack() {
        getChildFragmentManager().popBackStack();
    }
}
