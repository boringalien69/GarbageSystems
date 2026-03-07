package com.garbagesys.engine.faucet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.garbagesys.data.models.AirdropOpportunity
import com.garbagesys.data.models.AirdropStatus
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AirdropSubmitter(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val SCAN_JS = """
        (function() {
            var r = {hasWalletInput:false,hasSocialLogin:false,walletInputSelector:null,submitSelector:null,socialType:null};
            var h = document.body.innerHTML.toLowerCase();
            if (h.includes('twitter')||h.includes('x.com')) { r.hasSocialLogin=true; r.socialType='Twitter/X'; }
            if (h.includes('discord')&&h.includes('connect')) { r.hasSocialLogin=true; r.socialType=(r.socialType?r.socialType+', ':'')+' Discord'; }
            if (h.includes('telegram')&&h.includes('connect')) { r.hasSocialLogin=true; r.socialType=(r.socialType?r.socialType+', ':'')+' Telegram'; }
            var inputs=document.querySelectorAll('input');
            for(var i=0;i<inputs.length;i++){
                var inp=inputs[i];
                var c=((inp.placeholder||'')+(inp.name||'')+(inp.id||'')+(inp.className||'')).toLowerCase();
                if(c.includes('0x')||c.includes('wallet')||c.includes('address')){
                    r.hasWalletInput=true;
                    if(inp.id) r.walletInputSelector='#'+inp.id;
                    else if(inp.name) r.walletInputSelector='input[name="'+inp.name+'"]';
                    else r.walletInputSelector='input:nth-of-type('+(i+1)+')';
                    break;
                }
            }
            if(r.hasWalletInput){
                var btns=document.querySelectorAll('button,input[type="submit"]');
                for(var j=0;j<btns.length;j++){
                    var t=(btns[j].innerText||btns[j].value||'').toLowerCase();
                    if(t.includes('claim')||t.includes('submit')||t.includes('join')||t.includes('apply')){
                        r.submitSelector=btns[j].id?'#'+btns[j].id:'button:nth-of-type('+(j+1)+')';
                        break;
                    }
                }
            }
            return JSON.stringify(r);
        })();
    """.trimIndent()

    private fun fillJS(wallet: String, sel: String, sub: String?) = """
        (function(){
            try{
                var inp=document.querySelector('$sel');
                if(!inp) return '{"success":false}';
                var s=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
                s.call(inp,'$wallet');
                inp.dispatchEvent(new Event('input',{bubbles:true}));
                inp.dispatchEvent(new Event('change',{bubbles:true}));
                ${if (sub != null) "setTimeout(function(){var b=document.querySelector('$sub');if(b)b.click();},800);" else ""}
                return '{"success":true}';
            }catch(e){return '{"success":false}';}
        })();
    """.trimIndent()

    suspend fun trySubmit(airdrop: AirdropOpportunity, walletAddress: String): AirdropOpportunity = withContext(Dispatchers.Main) {
        var wv: WebView? = null
        try {
            val scan = suspendCoroutine<String> { cont ->
                mainHandler.post {
                    wv = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 12)"
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                mainHandler.postDelayed({ view.evaluateJavascript(SCAN_JS) { cont.resume(it ?: "{}") } }, 3000)
                            }
                            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                                if (req.isForMainFrame) cont.resume("{\"loadError\":true}")
                            }
                        }
                        loadUrl(airdrop.url)
                    }
                }
            }
            val hasWallet = scan.contains("\"hasWalletInput\":true")
            val hasSocial = scan.contains("\"hasSocialLogin\":true")
            val isError   = scan.contains("\"loadError\":true")
            val walletSel = Regex("\"walletInputSelector\":\"([^\"]+)\"").find(scan)?.groupValues?.get(1)
            val submitSel = Regex("\"submitSelector\":\"([^\"]+)\"").find(scan)?.groupValues?.get(1)
            val socialType = Regex("\"socialType\":\"([^\"]+)\"").find(scan)?.groupValues?.get(1)
            when {
                isError -> airdrop.copy(status = AirdropStatus.FAILED, requiresAction = "Page failed to load")
                hasSocial && !hasWallet -> airdrop.copy(status = AirdropStatus.REQUIRES_ACTION, requiresAction = "Requires $socialType — open manually")
                hasWallet && walletSel != null -> {
                    val filled = suspendCoroutine<Boolean> { cont ->
                        mainHandler.post { wv?.evaluateJavascript(fillJS(walletAddress, walletSel, submitSel)) { cont.resume(it?.contains("true") == true) } }
                    }
                    if (filled) airdrop.copy(status = AirdropStatus.SUBMITTED, submittedAt = System.currentTimeMillis())
                    else airdrop.copy(status = AirdropStatus.FAILED, requiresAction = "Auto-fill failed")
                }
                else -> airdrop.copy(status = AirdropStatus.REQUIRES_ACTION, requiresAction = "No wallet input found — open manually")
            }
        } catch (e: Exception) {
            airdrop.copy(status = AirdropStatus.FAILED, requiresAction = e.message?.take(60))
        } finally {
            mainHandler.post { wv?.destroy() }
        }
    }
}
