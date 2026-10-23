# -*- coding: utf-8 -*-
"""AI 视频工作台 — 免安装桌面端（独立窗口）"""
import os
import sys
import json
import urllib.request

def find_html():
    here = os.path.dirname(os.path.abspath(__file__)) if not getattr(sys, "frozen", False) else ""
    cands = []
    if getattr(sys, "frozen", False):
        base = os.path.dirname(sys.executable)
        mei = getattr(sys, "_MEIPASS", "")
        cands += [
            os.path.join(base, "app", "index.html"),
            os.path.join(base, "index.html"),
            os.path.join(mei, "app", "index.html") if mei else "",
        ]
    else:
        cands += [
            os.path.join(here, "app", "index.html"),
            os.path.join(here, "index.html"),
            os.path.join(os.path.dirname(here), "index.html"),
        ]
    for p in cands:
        if p and os.path.isfile(p):
            return os.path.abspath(p)
    return None

def app_base():
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    argv0 = sys.argv[0] if sys.argv and sys.argv[0] else "."
    return os.path.dirname(os.path.abspath(argv0))

def data_dir():
    base = app_base()
    d = os.path.join(base, "data")
    try:
        os.makedirs(d, exist_ok=True)
        return d
    except Exception:
        d = os.path.join(os.path.expanduser("~"), "AppData", "Local", "AIVideoWorkbench")
        os.makedirs(d, exist_ok=True)
        return d

def load_download_dir():
    path = os.path.join(data_dir(), "download_dir.txt")
    try:
        if os.path.isfile(path):
            v = open(path, "r", encoding="utf-8").read().strip()
            if v and os.path.isdir(v):
                return v
    except Exception:
        pass
    return ""

def save_download_dir(folder):
    path = os.path.join(data_dir(), "download_dir.txt")
    try:
        open(path, "w", encoding="utf-8").write(folder or "")
        return True
    except Exception:
        return False

def app_state_path():
    return os.path.join(data_dir(), "app-state.json")

def load_app_state():
    path = app_state_path()
    try:
        if os.path.isfile(path):
            return json.loads(open(path, "r", encoding="utf-8").read() or "{}")
    except Exception:
        pass
    return {}

def save_app_state_to_disk(state):
    try:
        open(app_state_path(), "w", encoding="utf-8").write(
            json.dumps(state or {}, ensure_ascii=False, indent=2)
        )
        return True
    except Exception:
        return False

class Api:
    def choose_folder(self):
        """弹出系统文件夹选择框，返回路径或 None。"""
        try:
            import tkinter as tk
            from tkinter import filedialog
        except Exception as e:
            return {"ok": False, "error": "tkinter 不可用: %s" % e}
        try:
            root = tk.Tk()
            root.withdraw()
            root.attributes("-topmost", True)
            initial = load_download_dir() or os.path.expanduser("~")
            folder = filedialog.askdirectory(initialdir=initial, title="选择视频下载文件夹")
            root.destroy()
        except Exception as e:
            return {"ok": False, "error": str(e)}
        if not folder:
            return {"ok": False, "error": "cancelled", "cancelled": True}
        folder = os.path.abspath(folder)
        save_download_dir(folder)
        return {"ok": True, "folder": folder}

    def get_download_dir(self):
        return load_download_dir()

    def get_app_state(self):
        """读本机 data\\app-state.json（Key/任务/模型等），供界面恢复。"""
        return load_app_state()

    def save_app_state(self, state):
        """把界面配置写入本机 data\\app-state.json，重启不丢。"""
        ok = save_app_state_to_disk(state)
        if ok and isinstance(state, dict):
            dd = state.get("aiv_download_dir")
            if dd:
                save_download_dir(str(dd))
        return {"ok": bool(ok), "path": app_state_path() if ok else ""}

    def _resolve_folder(self, folder=None):
        folder = (folder or "").strip() or load_download_dir()
        if not folder:
            folder = os.path.join(data_dir(), "backups")
        try:
            os.makedirs(folder, exist_ok=True)
        except Exception:
            folder = os.path.join(data_dir(), "backups")
            os.makedirs(folder, exist_ok=True)
        return os.path.abspath(folder)

    def export_backup(self, content, filename=None):
        """将备份 JSON 写到下载目录（未设置则 data\\backups），返回路径。"""
        folder = self._resolve_folder()
        filename = (filename or "ai-video-backup.json").replace("\\", "_").replace("/", "_")
        if not filename.lower().endswith(".json"):
            filename += ".json"
        dest = os.path.join(folder, filename)
        try:
            if isinstance(content, (dict, list)):
                text = json.dumps(content, ensure_ascii=False, indent=2)
            else:
                text = str(content)
            open(dest, "w", encoding="utf-8").write(text)
            return {"ok": True, "path": dest, "folder": folder}
        except Exception as e:
            return {"ok": False, "error": str(e)}

    def save_video(self, url, filename, folder=None):
        """下载视频到设置中的文件夹；无设置时用 data\\backups 并记住。"""
        folder = self._resolve_folder(folder)
        try:
            os.makedirs(folder, exist_ok=True)
        except Exception as e:
            return {"ok": False, "error": "无法创建目录: %s" % e}
        filename = (filename or "video.mp4").replace("\\", "_").replace("/", "_")
        if not filename.lower().endswith((".mp4", ".webm", ".mov", ".m4v")):
            filename += ".mp4"
        dest = os.path.join(folder, filename)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "AI-Video-Workbench/1.0"})
            with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as f:
                while True:
                    chunk = resp.read(1024 * 256)
                    if not chunk:
                        break
                    f.write(chunk)
            if folder != os.path.join(data_dir(), "backups"):
                save_download_dir(folder)
            return {"ok": True, "path": dest, "folder": folder}
        except Exception as e:
            return {"ok": False, "error": str(e)}

def main():
    try:
        import webview
    except ImportError:
        import ctypes
        ctypes.windll.user32.MessageBoxW(
            0,
            "缺少运行库 pywebview。\n请先在命令行执行：python -m pip install pywebview",
            "AI 视频工作台",
            16,
        )
        return 1

    html = find_html()
    if not html:
        import ctypes
        ctypes.windll.user32.MessageBoxW(
            0,
            "未找到 app\\index.html\n请保持桌面端文件夹结构完整。",
            "AI 视频工作台",
            16,
        )
        return 1

    url = "file:///" + html.replace("\\", "/")
    api = Api()
    # WebView 本机数据固定在 data\webview，避免进临时目录导致重启丢失
    try:
        wv = os.path.join(data_dir(), "webview")
        os.makedirs(wv, exist_ok=True)
        os.environ["WEBVIEW2_USER_DATA_FOLDER"] = wv
    except Exception:
        pass
    webview.create_window(
        "AI 视频工作台",
        url,
        js_api=api,
        width=1280,
        height=860,
        min_size=(960, 640),
        background_color="#F3F5F4",
        easy_drag=True,
        text_select=True,
        confirm_close=False,
    )

    def _force_exit(*_args, **_kwargs):
        """点窗口关闭后彻底退出，避免 WebView 残留占用文件夹。"""
        try:
            os._exit(0)
        except Exception:
            pass

    try:
        win = webview.windows[0] if getattr(webview, "windows", None) else None
        if win is not None:
            try:
                win.events.closed += _force_exit
            except Exception:
                pass
            try:
                win.events.closing += _force_exit
            except Exception:
                pass
    except Exception:
        pass

    try:
        webview.start(gui="edgechromium", debug=False)
    except Exception:
        try:
            webview.start(debug=False)
        except Exception:
            pass
    _force_exit()
    return 0

if __name__ == "__main__":
    sys.exit(main() or 0)
