const { translate } = require('bing-translate-api');
(async () => {
  try {
    const r = await translate('Xin chào hôm nay', null, 'zh-Hans');
    console.log('OK1', JSON.stringify(r).slice(0,200));
    const r2 = await translate('Bạn vui lòng quay lại sau $0$ giờ nữa.', 'vi', 'zh-Hans');
    console.log('OK2', JSON.stringify(r2).slice(0,200));
  } catch (e) { console.log('ERR', String(e).slice(0,300)); }
})();
