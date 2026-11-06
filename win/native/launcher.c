#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <wchar.h>

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR args, int show) {
    wchar_t home[32768], java[32768], command[65536];
    DWORD size = GetModuleFileNameW(NULL, home, 32768);
    if (!size || size >= 32768) return 1;
    wchar_t *slash = wcsrchr(home, L'\\');
    if (!slash) return 1;
    *slash = 0;
    if (swprintf(java, 32768, L"%ls\\runtime\\bin\\javaw.exe", home) < 0 ||
        swprintf(command, 65536, L"\"%ls\" -Xmx256m -Dntv.home=\"%ls\" -jar \"%ls\\app\\ntv-tv.jar\"", java, home, home) < 0) return 1;
    STARTUPINFOW startup = {0}; PROCESS_INFORMATION process = {0}; startup.cb = sizeof(startup);
    if (!CreateProcessW(java, command, NULL, NULL, FALSE, 0, NULL, home, &startup, &process)) {
        MessageBoxW(NULL, L"无法启动网页电视。请完整解压，并保留 runtime、app 和 player 目录。", L"网页电视", MB_OK | MB_ICONERROR);
        return 1;
    }
    CloseHandle(process.hThread); CloseHandle(process.hProcess);
    return 0;
}
