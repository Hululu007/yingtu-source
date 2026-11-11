package com.yingtu.plus.web;

/**
 * WebHome SDK 注入脚本。
 *
 * 在 WebView onPageFinished 后注入，向页面暴露 window.fongmi / window.fm：
 * 这是 webhtv「WebHome」能力的核心——让 HTML/CSS/JS 自定义首页可以调用
 * App 的 Native 能力（搜索、播放、跨域请求、资源代理、缓存、最近观看等）。
 *
 * 兼容性：脚本本体仅用 ES5/ES2015 语法（var/function/箭头函数不混用过新特性），
 * 以适配 minSdk 24 旧 WebView 内核。
 */
public final class WebHomeSdk {

    private WebHomeSdk() {}

    /** bridge 的 @JavascriptInterface 对象名 */
    public static final String BRIDGE = "fongmiBridge";

    public static String script(boolean isLeanback) {
        String mode = isLeanback ? "leanback" : "mobile";
        return "(function(){\n" +
            "  if(window.fm&&window.fongmi){window.dispatchEvent(new CustomEvent('fmsdk'));return;}\n" +
            "  if(document&&document.documentElement)document.documentElement.classList.add('fm-native');\n" +
            "  window.fongmiClient={mode:'" + mode + "',isLeanback:" + isLeanback + "};\n" +
            "  var callbacks={};\n" +
            "  var seq=0;\n" +
            "  function invoke(method,payload){\n" +
            "    return new Promise(function(resolve,reject){\n" +
            "      var id='fm_'+Date.now()+'_'+(++seq);\n" +
            "      callbacks[id]={resolve:resolve,reject:reject};\n" +
            "      " + BRIDGE + ".invoke(id,method,JSON.stringify(payload||{}));\n" +
            "    });\n" +
            "  }\n" +
            "  function hydrate(data){\n" +
            "    if(!data||!data.__fmResultId)return data;\n" +
            "    var resultId=data.__fmResultId;\n" +
            "    var length=" + BRIDGE + ".resultLength(resultId);\n" +
            "    var text='';\n" +
            "    for(var start=0;start<length;start+=60000)text+=" + BRIDGE + ".resultChunk(resultId,start);\n" +
            "    " + BRIDGE + ".clearResult(resultId);\n" +
            "    return JSON.parse(text);\n" +
            "  }\n" +
            "  window.fongmiNative={\n" +
            "    resolve:function(id,data){ if(callbacks[id]){ callbacks[id].resolve(hydrate(data)); delete callbacks[id]; } },\n" +
            "    reject:function(id,error){ if(callbacks[id]){ callbacks[id].reject(new Error(error||'')); delete callbacks[id]; } }\n" +
            "  };\n" +
            "  if(!window.__fmUrlHook&&window.history){\n" +
            "    window.__fmUrlHook=true;\n" +
            "    var emit=function(){window.dispatchEvent(new CustomEvent('fmurlchange',{detail:{url:location.href}}));};\n" +
            "    var rawPush=history.pushState;\n" +
            "    var rawReplace=history.replaceState;\n" +
            "    history.pushState=function(){var r=rawPush.apply(this,arguments);emit();return r;};\n" +
            "    history.replaceState=function(){var r=rawReplace.apply(this,arguments);emit();return r;};\n" +
            "    window.addEventListener('popstate',emit);\n" +
            "  }\n" +
            "  var player={\n" +
            "    playUrl:function(url,title,options){return invoke('player.playUrl',Object.assign({},options||{},{url:url,title:title}));},\n" +
            "    playVod:function(siteKey,vodId,title,pic,options){return invoke('player.playVod',Object.assign({},options||{},{siteKey:siteKey,vodId:vodId,title:title,pic:pic}));},\n" +
            "    playVodInline:function(payload){return invoke('player.playVodInline',payload||{});},\n" +
            "    preloadArtwork:function(pic,wallPic){return invoke('player.preloadArtwork',{pic:pic,wallPic:wallPic});},\n" +
            "    control:function(action){return invoke('player.control',{action:action});},\n" +
            "    status:function(){return invoke('player.status',{});}\n" +
            "  };\n" +
            "  var net={\n" +
            "    request:function(url,options){return invoke('net.request',Object.assign({},options||{},{url:url}));},\n" +
            "    resourceUrl:function(url,options){return " + BRIDGE + ".resourceUrl(url,JSON.stringify(options||{}));}\n" +
            "  };\n" +
            "  var cache={\n" +
            "    get:function(key,rule){return invoke('cache.get',{key:key,rule:rule});},\n" +
            "    set:function(key,value,rule){return invoke('cache.set',{key:key,value:value,rule:rule});},\n" +
            "    del:function(key,rule){return invoke('cache.del',{key:key,rule:rule});}\n" +
            "  };\n" +
            "  var pan={\n" +
            "    check:function(items){return invoke('pan.check',{items:items});},\n" +
            "    play:function(payload){return invoke('pan.play',payload||{});}\n" +
            "  };\n" +
            "  var ext={\n" +
            "    info:function(){return invoke('ext.info',{});},\n" +
            "    log:function(message,data){return invoke('ext.log',{message:message,data:data});},\n" +
            "    toast:function(message){return invoke('ext.toast',{message:message});}\n" +
            "  };\n" +
            "  var ui={\n" +
            "    setToolbar:function(visible){return invoke('ui.setToolbar',{visible:visible!==false});},\n" +
            "    setChrome:function(options){return invoke('ui.setChrome',options||{});},\n" +
            "    restoreChrome:function(){return invoke('ui.restoreChrome',{});},\n" +
            "    getViewport:function(){return invoke('ui.getViewport',{});}\n" +
            "  };\n" +
            "  window.fongmi={invoke:invoke,player:player,net:net,cache:cache,\n" +
            "    app:{\n" +
            "      search:function(keyword,options){return invoke('app.search',Object.assign({},options||{},{keyword:keyword}));},\n" +
            "      openVod:function(){return invoke('app.openVod',{});},\n" +
            "      openLive:function(){return invoke('app.openLive',{});},\n" +
            "      openKeep:function(){return invoke('app.openKeep',{});},\n" +
            "      openSetting:function(){return invoke('app.openSetting',{});},\n" +
            "      history:function(){return invoke('app.history',{});}\n" +
            "    },\n" +
            "    pan:pan,\n" +
            "    ext:ext,\n" +
            "    device:{info:function(){return invoke('device.info',{});}},\n" +
            "    site:{info:function(){return invoke('site.info',{});}},\n" +
            "    config:{info:function(){return invoke('config.info',{});}},\n" +
            "    ui:ui,\n" +
            "    navigation:{\n" +
            "      back:function(){return invoke('navigation.back',{});},\n" +
            "      reload:function(){return invoke('navigation.reload',{});}\n" +
            "    }\n" +
            "  };\n" +
            "  window.fm={\n" +
            "    req:net.request,\n" +
            "    res:net.resourceUrl,\n" +
            "    play:player.playUrl,\n" +
            "    vod:player.playVod,\n" +
            "    vodInline:player.playVodInline,\n" +
            "    preloadArtwork:player.preloadArtwork,\n" +
            "    ctrl:player.control,\n" +
            "    stat:player.status,\n" +
            "    search:window.fongmi.app.search,\n" +
            "    openVod:window.fongmi.app.openVod,\n" +
            "    openLive:window.fongmi.app.openLive,\n" +
            "    openKeep:window.fongmi.app.openKeep,\n" +
            "    openSetting:window.fongmi.app.openSetting,\n" +
            "    history:window.fongmi.app.history,\n" +
            "    pan:pan,\n" +
            "    check:window.fongmi.pan.check,\n" +
            "    cache:cache,\n" +
            "    ext:ext,\n" +
            "    ui:ui,\n" +
            "    device:window.fongmi.device.info,\n" +
            "    site:window.fongmi.site.info,\n" +
            "    config:window.fongmi.config.info,\n" +
            "    back:window.fongmi.navigation.back,\n" +
            "    reload:window.fongmi.navigation.reload\n" +
            "  };\n" +
            "  window.dispatchEvent(new CustomEvent('fmsdk'));\n" +
            "})();";
    }


    /** 带调试钩子的版本：调试对话框用，接管 console/fetch/XHR 上报给 fongmiBridge。 */
    public static String script(boolean isLeanback, boolean debugTools) {
        String base = script(isLeanback);
        if (!debugTools) return base;
        int tail = base.lastIndexOf("})();");
        if (tail < 0) return base;
        return base.substring(0, tail) + DEBUG_HOOK + base.substring(tail);
    }

    private static final String DEBUG_HOOK = """

                  if(!window.__fmConsoleHook){
                    window.__fmConsoleHook=true;
                    ['log','info','warn','error','debug'].forEach(function(level){
                      const raw=console[level]||console.log;
                      console[level]=function(){
                        const args=Array.prototype.slice.call(arguments);
                        try{fongmiBridge.console(level,args.map(function(v){try{return typeof v==='string'?v:JSON.stringify(v);}catch(e){return String(v);}}).join(' '));}catch(e){}
                        return raw&&raw.apply(console,args);
                      };
                    });
                  }
                  if(!window.__fmNetworkHook){
                    window.__fmNetworkHook=true;
                    const absolute=function(url){try{return new URL(String(url),location.href).href;}catch(e){return String(url||'');}};
                    const clip=function(value){value=String(value||'');return value.length>2000?value.slice(0,2000)+'\\n...truncated':value;};
                    const bodyText=function(body){
                      if(!body)return '';
                      if(typeof body==='string')return 'payload:\\n'+clip(body);
                      if(body instanceof URLSearchParams)return 'payload:\\n'+clip(body.toString());
                      if(body instanceof FormData){
                        const out=[];
                        try{body.forEach(function(v,k){out.push(k+'='+(v&&v.name?'[file '+v.name+']':String(v)));});}catch(e){}
                        return 'payload:\\n'+clip(out.join('\\n'));
                      }
                      return 'payloadType='+Object.prototype.toString.call(body)+'\\npayloadBytes='+String(body).length;
                    };
                    const headers=function(headers){
                      const out=[];
                      try{
                        if(headers&&headers.forEach)headers.forEach(function(v,k){out.push(k+': '+v);});
                        else if(headers)Object.keys(headers).forEach(function(k){out.push(k+': '+headers[k]);});
                      }catch(e){}
                      return out.join('\\n');
                    };
                    const rawFetch=window.fetch;
                    if(rawFetch){
                      window.fetch=function(input,init){
                        const started=Date.now();
                        const method=(init&&init.method)||(input&&input.method)||'GET';
                        const url=absolute(input&&input.url?input.url:input);
                        const requestHeaders=headers((init&&init.headers)||(input&&input.headers));
                        const body=bodyText(init&&init.body);
                        try{fongmiBridge.network('FETCH_START',method,url,0,0,[requestHeaders,body].filter(Boolean).join('\\n'));}catch(e){}
                        return rawFetch.apply(this,arguments).then(function(resp){
                          try{fongmiBridge.network('FETCH_DONE',method,url,resp.status||0,Date.now()-started,['type='+(resp.type||''),'headers:',headers(resp.headers)].join('\\n'));}catch(e){}
                          return resp;
                        }).catch(function(err){
                          try{fongmiBridge.network('FETCH_ERROR',method,url,0,Date.now()-started,String(err&&err.message||err));}catch(e){}
                          throw err;
                        });
                      };
                    }
                    const RawXHR=window.XMLHttpRequest;
                    if(RawXHR&&RawXHR.prototype){
                      const rawOpen=RawXHR.prototype.open;
                      const rawSend=RawXHR.prototype.send;
                      RawXHR.prototype.open=function(method,url){
                        this.__fmMethod=method||'GET';
                        this.__fmUrl=absolute(url);
                        return rawOpen.apply(this,arguments);
                      };
                      RawXHR.prototype.send=function(){
                        const xhr=this;
                        const started=Date.now();
                        const body=arguments.length?bodyText(arguments[0]):'';
                        try{fongmiBridge.network('XHR_START',xhr.__fmMethod||'GET',xhr.__fmUrl||'',0,0,body);}catch(e){}
                        xhr.addEventListener('loadend',function(){
                          try{fongmiBridge.network('XHR_DONE',xhr.__fmMethod||'GET',xhr.__fmUrl||'',xhr.status||0,Date.now()-started,[xhr.statusText||'',xhr.getAllResponseHeaders&&xhr.getAllResponseHeaders()||''].filter(Boolean).join('\\n'));}catch(e){}
                        });
                        xhr.addEventListener('error',function(){
                          try{fongmiBridge.network('XHR_ERROR',xhr.__fmMethod||'GET',xhr.__fmUrl||'',xhr.status||0,Date.now()-started,'error');}catch(e){}
                        });
                        return rawSend.apply(this,arguments);
                      };
                    }
                  }
                """;

    /** 注入根元素 CSS 变量（视口 / 安全区 / chrome 模式）。 */
    public static String viewportVars(int widthPx, int heightPx, int safeTop, int safeBottom,
                                      int statusBarHeight, String chromeMode) {
        return "(function(){try{var r=document.documentElement;if(!r)return;" +
            "r.style.setProperty('--fm-web-width','" + widthPx + "px');" +
            "r.style.setProperty('--fm-web-height','" + heightPx + "px');" +
            "r.style.setProperty('--fm-safe-top','" + safeTop + "px');" +
            "r.style.setProperty('--fm-safe-right','0px');" +
            "r.style.setProperty('--fm-safe-bottom','" + safeBottom + "px');" +
            "r.style.setProperty('--fm-safe-left','0px');" +
            "r.style.setProperty('--fm-safe-bottom-max','" + safeBottom + "px');" +
            "r.style.setProperty('--fm-status-bar-height','" + statusBarHeight + "px');" +
            "r.style.setProperty('--fm-navigation-bar-height','" + safeBottom + "px');" +
            "r.style.setProperty('--fm-chrome-mode','" + chromeMode + "');" +
            "window.dispatchEvent(new CustomEvent('fmviewport',{detail:{" +
            "webWidth:" + widthPx + ",webHeight:" + heightPx + ",safeTop:" + safeTop +
            ",safeBottom:" + safeBottom + ",statusBarHeight:" + statusBarHeight +
            ",chromeMode:'" + chromeMode + "'}}));" +
            "}catch(e){}})();";
    }

    /** 触发自定义事件（fmresume / fmpause）。 */
    public static String dispatchEvent(String event, String detailJson) {
        return "(function(){try{window.dispatchEvent(new CustomEvent('" + event +
            "',{detail:" + (detailJson == null ? "{}" : detailJson) + "}));}catch(e){}})();";
    }
}
