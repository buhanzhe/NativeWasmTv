// LGPL-2.1-or-later. Keep logcat output and independently mirror into app files.
const fs = require('fs'), path = require('path');
module.exports = function(root) {
  const dir = path.join(root, 'ijkmedia/ijksdl');
  fs.copyFileSync(path.join(__dirname, 'ntv_app_log.c'), path.join(dir, 'ntv_app_log.c'));
  const header = path.join(dir, 'ijksdl_log.h');
  let text = fs.readFileSync(header, 'utf8').replace(/\r\n/g, '\n');
  if (!text.includes('void ntv_app_log_vprint')) {
    const anchor = '#include <android/log.h>';
    if (!text.includes(anchor)) throw new Error('Unexpected IJK log header');
    text = text.replace(anchor, anchor + '\n#include <stdarg.h>\n'
      + 'void ntv_app_log_vprint(int, const char *, const char *, va_list);\n'
      + 'void ntv_app_log_print(int, const char *, const char *, ...);');
    text = text.replaceAll('__android_log_vprint(level, TAG,', 'ntv_app_log_vprint(level, TAG,')
      .replaceAll('__android_log_print(level, TAG,', 'ntv_app_log_print(level, TAG,');
    fs.writeFileSync(header, text);
  }
  const makefile = path.join(dir, 'Android.mk');
  text = fs.readFileSync(makefile, 'utf8');
  if (!text.includes('LOCAL_SRC_FILES += ntv_app_log.c')) {
    const anchor = 'LOCAL_SRC_FILES += ijksdl_extra_log.c';
    if (!text.includes(anchor)) throw new Error('Unexpected IJK SDL makefile');
    fs.writeFileSync(makefile, text.replace(anchor, anchor + '\nLOCAL_SRC_FILES += ntv_app_log.c'));
  }
};
if (require.main === module) module.exports(path.resolve(process.argv[2] || '.codex-tmp/ijkplayer-0.8.8'));
