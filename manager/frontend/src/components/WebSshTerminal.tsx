import { Clipboard, ClipboardPaste, X } from 'lucide-react';
import type { MouseEvent } from 'react';
import { useEffect, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { WebLinksAddon } from '@xterm/addon-web-links';
import '@xterm/xterm/css/xterm.css';
import { api } from '../lib/api';
import type { RemoteAccessSession } from '../lib/types';

type WebSshTerminalProps = {
  session: RemoteAccessSession;
  onClose: () => void;
};

type ServerMessage = {
  type: 'status' | 'output' | 'error' | 'closed';
  message?: string;
  data?: string;
};

const decoder = new TextDecoder();

export function WebSshTerminal({ session, onClose }: WebSshTerminalProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const [status, setStatus] = useState(session.status);

  useEffect(() => {
    const terminal = new Terminal({
      cursorBlink: true,
      convertEol: true,
      fontFamily: 'Cascadia Mono, Consolas, Menlo, monospace',
      fontSize: 13,
      theme: {
        background: '#0b1117',
        foreground: '#d6e2ee',
        cursor: '#f7d774',
        selectionBackground: '#365a7a'
      }
    });
    const fitAddon = new FitAddon();
    terminal.loadAddon(fitAddon);
    terminal.loadAddon(new WebLinksAddon());
    terminalRef.current = terminal;
    fitAddonRef.current = fitAddon;
    if (containerRef.current) {
      terminal.open(containerRef.current);
      fitAddon.fit();
    }
    terminal.writeln(`Castrelyx WebSSH ${session.sshUser}@${session.targetHost}:${session.targetPort}`);
    terminal.writeln(`Key ${session.publicKeyFingerprint}`);

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(`${protocol}//${window.location.host}/api/remote-access/ssh-sessions/${session.id}/stream`);
    socketRef.current = socket;
    socket.addEventListener('open', () => {
      setStatus('CONNECTING');
      sendResize(socket, terminal);
    });
    socket.addEventListener('message', (event) => {
      const message = JSON.parse(String(event.data)) as ServerMessage;
      if (message.type === 'output' && message.data) {
        terminal.write(decodeBase64(message.data));
      } else if (message.type === 'status') {
        setStatus(message.message ?? 'CONNECTED');
        terminal.writeln(`\r\n${message.message ?? ''}`);
      } else if (message.type === 'error') {
        setStatus('ERROR');
        terminal.writeln(`\r\n${message.message ?? 'SSH session error'}`);
      } else if (message.type === 'closed') {
        setStatus('CLOSED');
      }
    });
    socket.addEventListener('close', () => setStatus('CLOSED'));
    terminal.onData((data) => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: 'input', data }));
      }
    });

    const resizeObserver = new ResizeObserver(() => {
      fitAddon.fit();
      sendResize(socket, terminal);
    });
    if (containerRef.current) {
      resizeObserver.observe(containerRef.current);
    }
    const keyHandler = (event: KeyboardEvent) => {
      if (!event.ctrlKey || !event.shiftKey) {
        return;
      }
      if (event.key.toLowerCase() === 'c') {
        event.preventDefault();
        void copySelection();
      } else if (event.key.toLowerCase() === 'v') {
        event.preventDefault();
        void pasteClipboard();
      }
    };
    document.addEventListener('keydown', keyHandler);
    return () => {
      document.removeEventListener('keydown', keyHandler);
      resizeObserver.disconnect();
      socket.close();
      terminal.dispose();
      void api.closeRemoteSshSession(session.id).catch(() => undefined);
    };
  }, [session]);

  async function copySelection() {
    const selection = terminalRef.current?.getSelection();
    if (selection) {
      await navigator.clipboard.writeText(selection);
    }
  }

  async function pasteClipboard() {
    const text = await navigator.clipboard.readText();
    if (text && socketRef.current?.readyState === WebSocket.OPEN) {
      socketRef.current.send(JSON.stringify({ type: 'input', data: text }));
    }
  }

  async function close() {
    await api.closeRemoteSshSession(session.id).catch(() => undefined);
    onClose();
  }

  function contextMenu(event: MouseEvent) {
    event.preventDefault();
    const selection = terminalRef.current?.getSelection();
    if (selection) {
      void copySelection();
    } else {
      void pasteClipboard();
    }
  }

  return (
    <section className="webssh-panel" aria-label="WebSSH terminal">
      <div className="webssh-toolbar">
        <div>
          <strong>{session.assetUid ?? session.agentId}</strong>
          <span>{session.sshUser}@{session.targetHost}:{session.targetPort}</span>
          <em>{status}</em>
        </div>
        <div className="webssh-actions">
          <button type="button" className="icon-button" aria-label="Copy terminal selection" onClick={() => void copySelection()}>
            <Clipboard size={17} />
          </button>
          <button type="button" className="icon-button" aria-label="Paste clipboard into terminal" onClick={() => void pasteClipboard()}>
            <ClipboardPaste size={17} />
          </button>
          <button type="button" className="icon-button" aria-label="Close SSH terminal" onClick={() => void close()}>
            <X size={17} />
          </button>
        </div>
      </div>
      <div className="webssh-terminal" ref={containerRef} onContextMenu={contextMenu} />
    </section>
  );
}

function sendResize(socket: WebSocket, terminal: Terminal) {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify({ type: 'resize', cols: terminal.cols, rows: terminal.rows }));
  }
}

function decodeBase64(value: string) {
  const binary = window.atob(value);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return decoder.decode(bytes);
}
