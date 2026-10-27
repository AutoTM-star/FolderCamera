// 카카오톡·네이버·인스타그램 등 앱 안 브라우저에서 열리면 다운로드가 안 되므로
// 자동으로 폰의 기본 브라우저(Chrome / 삼성 인터넷)로 다시 연다.
(function () {
  var ua = navigator.userAgent || "";
  var url = location.href;
  if (/KAKAOTALK/i.test(ua)) {
    location.href = "kakaotalk://web/openExternal?url=" + encodeURIComponent(url);
    return;
  }
  if (/Line\//i.test(ua)) {
    if (url.indexOf("openExternalBrowser=1") < 0) {
      location.href = url + (url.indexOf("?") < 0 ? "?" : "&") + "openExternalBrowser=1";
    }
    return;
  }
  var inApp = /NAVER\(inapp|Instagram|FBAN|FBAV|everytimeApp|DaumApps|wv\)/i.test(ua);
  if (inApp && /Android/i.test(ua)) {
    location.href =
      "intent://" + location.host + location.pathname + location.search +
      "#Intent;scheme=https;package=com.android.chrome;end";
  }
})();
