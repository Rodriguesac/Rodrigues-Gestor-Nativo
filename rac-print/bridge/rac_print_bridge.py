"""RAC Print - receptor de impressão LAN para Windows.

Executar pelo Python no Windows ou pelo executável do GitHub Actions.
Somente impressoras já instaladas no Windows. Não é um serviço de nuvem.
"""
from __future__ import annotations

import base64
import hmac
import json
import os
import queue
import secrets
import socket
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

APP_VERSION = "0.2.1"
PORT = 8765
MAX_BYTES = 15 * 1024 * 1024
CONFIG_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "RAC Print"
CONFIG_PATH = CONFIG_DIR / "config.json"


def config_load():
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    if CONFIG_PATH.exists():
        try:
            obj = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
            if len(obj.get("token", "")) >= 32:
                return obj
        except (ValueError, OSError):
            pass
    obj = {"token": secrets.token_hex(24), "printer": ""}
    config_save(obj)
    return obj


def config_save(config):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    temp = CONFIG_PATH.with_suffix(".tmp")
    temp.write_text(json.dumps(config, indent=2), encoding="utf-8")
    os.replace(temp, CONFIG_PATH)


def local_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 53))  # consulta de rota: não envia dados
            return sock.getsockname()[0]
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"


def list_printers():
    import win32print
    flags = win32print.PRINTER_ENUM_LOCAL | win32print.PRINTER_ENUM_CONNECTIONS
    return [entry[2] for entry in win32print.EnumPrinters(flags)]


def default_printer():
    import win32print
    return win32print.GetDefaultPrinter()


def thermal_black_and_white(image, threshold: int = 205):
    """Converte tons de cinza em preto sólido para drivers de impressora térmica.

    Pixels abaixo do limiar viram preto puro; acima, branco puro.
    A densidade térmica física continua sendo configurada no driver/impressora.
    """
    gray = image.convert("L")
    return gray.point(lambda level: 0 if level < threshold else 255).convert("RGB")


def spool_pdf(data: bytes, printer: str, copies: int):
    """Recebe PDF intacto; desenha cada página diretamente no GDI da impressora.

    A impressora térmica não processa PDF nativo. A conversão interna usa
    a resolução do driver, sem captura de tela e sem jpeg de baixa qualidade.
    """
    import fitz
    import win32con
    import win32ui
    from PIL import Image, ImageWin

    if not data.startswith(b"%PDF-"):
        raise ValueError("Arquivo não é um PDF válido.")
    doc = fitz.open(stream=data, filetype="pdf")
    if len(doc) < 1 or len(doc) > 40:
        doc.close()
        raise ValueError("PDF sem páginas ou com mais de 40 páginas.")
    if doc.needs_pass:
        doc.close()
        raise ValueError("PDF protegido por senha.")
    dc = win32ui.CreateDC()
    try:
        dc.CreatePrinterDC(printer)
        device_w = dc.GetDeviceCaps(win32con.HORZRES)
        device_h = dc.GetDeviceCaps(win32con.VERTRES)
        if min(device_w, device_h) <= 0:
            raise RuntimeError("Driver de impressão sem área válida.")
        for _ in range(copies):
            dc.StartDoc("RAC Print - Documento")
            for page in doc:
                # Resolução calculada exatamente para a largura do driver Windows.
                scale = device_w / max(1, page.rect.width)
                pixels = page.get_pixmap(matrix=fitz.Matrix(scale, scale),
                                         colorspace=fitz.csGRAY, alpha=False)
                gray = Image.frombytes("L", (pixels.width, pixels.height), pixels.samples)
                # A impressora só deve receber preto sólido e branco sólido;
                # suavização em cinza pode parecer desbotada no papel.
                img = thermal_black_and_white(gray)
                # Divide documentos compridos em folhas/bobinas suportadas pelo driver.
                y = 0
                while y < img.height:
                    strip = img.crop((0, y, img.width, min(img.height, y + device_h)))
                    dc.StartPage()
                    ImageWin.Dib(strip).draw(dc.GetHandleOutput(),
                                            (0, 0, device_w, strip.height))
                    dc.EndPage()
                    y += device_h
            dc.EndDoc()
    finally:
        try:
            dc.DeleteDC()
        except Exception:
            pass
        doc.close()


class PrintService:
    def __init__(self, config, backend=None, on_update=None):
        self.config = config
        self.backend = backend or spool_pdf
        self.on_update = on_update or (lambda message: None)
        self.jobs = {}
        self.lock = threading.Lock()
        self.queue = queue.Queue(maxsize=30)
        self.thread = threading.Thread(target=self._consume, daemon=True)
        self.thread.start()

    def selected_printer(self):
        return self.config.get("printer") or default_printer()

    def create_job(self, data, copies):
        job_id = str(uuid.uuid4())
        with self.lock:
            # Mantém apenas os 100 trabalhos recentes, sem armazenar PDFs no disco.
            while len(self.jobs) >= 100:
                self.jobs.pop(next(iter(self.jobs)))
            self.jobs[job_id] = {"status": "queued", "error": ""}
        try:
            self.queue.put_nowait((job_id, data, copies))
        except queue.Full:
            with self.lock:
                del self.jobs[job_id]
            raise ValueError("Fila de impressão cheia.")
        return job_id

    def job_status(self, job_id):
        with self.lock:
            return dict(self.jobs[job_id]) if job_id in self.jobs else None

    def _consume(self):
        while True:
            job_id, data, copies = self.queue.get()
            with self.lock:
                self.jobs[job_id]["status"] = "printing"
            try:
                self.backend(data, self.selected_printer(), copies)
                with self.lock:
                    self.jobs[job_id]["status"] = "spooled"
                self.on_update(f"Enviado à fila de impressão: {job_id[:8]}")
            except Exception as ex:
                with self.lock:
                    self.jobs[job_id].update(status="error", error=str(ex)[:300])
                self.on_update("Erro de impressão: " + str(ex)[:120])
            finally:
                self.queue.task_done()


def make_handler(service):
    class Handler(BaseHTTPRequestHandler):
        server_version = "RACPrint/" + APP_VERSION
        sys_version = ""

        def log_message(self, format, *args):
            # Não registrar URL, conteúdo ou chave do usuário.
            return

        def _send(self, code, data):
            payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(payload)

        def _allowed(self):
            given = self.headers.get("X-RAC-Token", "")
            return hmac.compare_digest(given, service.config["token"])

        def do_GET(self):
            if self.path == "/ping":
                self._send(200, {"ok": True, "version": APP_VERSION})
                return
            if not self._allowed():
                self._send(401, {"error": "Chave de conexão incorreta."})
                return
            if self.path == "/status":
                try:
                    printer = service.selected_printer()
                    self._send(200, {"ok": True, "printer": printer, "version": APP_VERSION})
                except Exception as exc:
                    self._send(503, {"error": "Impressora indisponível: " + str(exc)[:150]})
            elif self.path.startswith("/jobs/"):
                job = service.job_status(self.path[len("/jobs/"):])
                self._send(200, job) if job else self._send(404, {"error": "Trabalho não encontrado."})
            else:
                self._send(404, {"error": "Não encontrado."})

        def do_POST(self):
            if self.path != "/print":
                self._send(404, {"error": "Não encontrado."})
                return
            if not self._allowed():
                self._send(401, {"error": "Chave de conexão incorreta."})
                return
            if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/pdf":
                self._send(415, {"error": "Somente PDF é permitido."})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                copies = int(self.headers.get("X-Copies", "1"))
            except ValueError:
                self._send(400, {"error": "Cabeçalhos inválidos."})
                return
            if not 0 < length <= MAX_BYTES:
                self._send(413, {"error": "Arquivo vazio ou maior que 15 MB."})
                return
            if copies not in (1, 2, 3):
                self._send(400, {"error": "Cópias devem estar entre 1 e 3."})
                return
            data = self.rfile.read(length)
            if len(data) != length or not data.startswith(b"%PDF-"):
                self._send(400, {"error": "PDF inválido ou incompleto."})
                return
            try:
                job_id = service.create_job(data, copies)
            except ValueError as ex:
                self._send(429, {"error": str(ex)})
                return
            self._send(202, {"id": job_id, "status": "queued"})

    return Handler


def run_server(service, host="0.0.0.0", port=PORT):
    http = ThreadingHTTPServer((host, port), make_handler(service))
    http.daemon_threads = True
    return http


def start_gui():
    import tkinter as tk
    from tkinter import ttk, messagebox

    cfg = config_load()
    app = tk.Tk()
    app.title("RAC Print • Receptor Windows")
    app.geometry("490x420")
    app.minsize(460, 395)
    app.configure(background="#f7f6fa")
    header = tk.Frame(app, background="#54309b", padx=18, pady=14)
    header.pack(fill="x")
    tk.Label(header, text="RAC Print", font=("Segoe UI", 20, "bold"), fg="white", bg="#54309b").pack(anchor="w")
    tk.Label(header, text="Receba documentos pelo Wi-Fi do celular", font=("Segoe UI", 10), fg="#ded4f1", bg="#54309b").pack(anchor="w")

    body = tk.Frame(app, background="#f7f6fa", padx=18, pady=16)
    body.pack(fill="both", expand=True)
    tk.Label(body, text="IP do PC (no celular):", bg="#f7f6fa", font=("Segoe UI", 10, "bold")).pack(anchor="w")
    address = f"{local_ip()}:{PORT}"
    tk.Label(body, text=address, font=("Consolas", 15), fg="#54309b", bg="#f7f6fa").pack(anchor="w")
    tk.Label(body, text="Chave de conexão (somente na rede local):", bg="#f7f6fa", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(12, 0))
    token_text = tk.Entry(body, font=("Consolas", 10), width=54)
    token_text.insert(0, cfg["token"])
    token_text.config(state="readonly")
    token_text.pack(anchor="w", pady=(3, 4), fill="x")

    def copy_connection():
        app.clipboard_clear()
        app.clipboard_append(f"IP: {address}\nChave: {cfg['token']}")
        state_var.set("Endereço e chave copiados")
    ttk.Button(body, text="Copiar dados da conexão", command=copy_connection).pack(anchor="w", pady=3)
    tk.Label(body, text="Impressora do Windows:", bg="#f7f6fa", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(12, 1))
    try:
        printers = list_printers()
    except Exception as ex:
        printers = []
        messagebox.showerror("Impressoras", "Falha ao consultar impressoras: " + str(ex))
    printer = tk.StringVar(value=cfg.get("printer") or (default_printer() if printers else ""))
    choices = ttk.Combobox(body, textvariable=printer, state="readonly", values=printers, width=60)
    choices.pack(fill="x", pady=(2, 8))

    def save_printer(*_):
        cfg["printer"] = printer.get()
        config_save(cfg)
    choices.bind("<<ComboboxSelected>>", save_printer)
    state_var = tk.StringVar(value="Preparando receptor...")
    tk.Label(body, textvariable=state_var, wraplength=420, justify="left", fg="#3e3850", bg="#f7f6fa").pack(anchor="w", pady=(7, 2))

    def status_update(message):
        app.after(0, lambda: state_var.set(message))
    service = PrintService(cfg, on_update=status_update)
    try:
        server = run_server(service)
    except OSError as ex:
        messagebox.showerror("RAC Print", "Não foi possível abrir a porta 8765: " + str(ex))
        app.destroy()
        return
    threading.Thread(target=server.serve_forever, daemon=True).start()
    state_var.set("Ativo • Aguardando impressões na rede local")

    def test_print():
        # PDF de teste simples gerado usando PyMuPDF; testará o driver configurado.
        try:
            import fitz
            doc = fitz.open()
            page = doc.new_page(width=226, height=210)
            page.insert_text((12, 25), "RAC PRINT - TESTE", fontsize=12)
            page.insert_text((12, 50), "Impressora conectada", fontsize=10)
            page.insert_text((12, 80), "Rodrigues Acai e Cia", fontsize=10)
            data = doc.tobytes()
            doc.close()
            service.create_job(data, 1)
            state_var.set("Cupom de teste colocado na fila")
        except Exception as ex:
            messagebox.showerror("Teste", str(ex))
    ttk.Button(body, text="Imprimir teste no PC", command=test_print).pack(anchor="w", pady=(4, 2))
    tk.Label(body, text="Mantenha o RAC Print aberto e os aparelhos no mesmo Wi-Fi.",
             bg="#f7f6fa", fg="#777080", wraplength=430).pack(anchor="w", pady=(9, 0))

    def close():
        server.shutdown()
        server.server_close()
        app.destroy()
    app.protocol("WM_DELETE_WINDOW", close)
    app.mainloop()


if __name__ == "__main__":
    start_gui()
