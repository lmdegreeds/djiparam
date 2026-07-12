#!/usr/bin/env python3
"""Drive the DJI RC 2 system shell over telnet (com.dpad.fuli busybox telnetd :2323).

The shell is `/system/bin/sh` running as UID 1000 (system), no login prompt. The PTY
echoes input, so we anchor on the EXPANDED exit-code marker `__RC2_END__<digits>`
(the echoed command line only ever contains the literal `__RC2_END__$?`). Prints each
command's stdout; process exit code = the last command's exit code.

The RC telnet host is taken from --host or the RC2_HOST environment variable.

Usage:
    RC2_HOST=<rc-ip> python rc2sh.py "id"
    python rc2sh.py --host <rc-ip> "pm install -r -g /sdcard/DjiParam.apk"
    python rc2sh.py --host <rc-ip> --port 2323 "getprop ro.build.display.id"
"""
import argparse
import os
import re
import sys
import warnings

warnings.filterwarnings("ignore")
import telnetlib  # noqa: E402

END = "__RC2_END__"
CODE = re.compile(rb"__RC2_END__(\d+)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=os.environ.get("RC2_HOST"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("RC2_PORT", "2323")))
    ap.add_argument("--timeout", type=float, default=90.0)
    ap.add_argument("cmds", nargs="+", help="shell commands to run in sequence")
    a = ap.parse_args()

    if not a.host:
        print("no RC host: pass --host <ip> or set RC2_HOST", file=sys.stderr)
        return 2

    try:
        tn = telnetlib.Telnet(a.host, a.port, timeout=a.timeout)
    except Exception as e:
        print(f"connect {a.host}:{a.port} failed: {e}", file=sys.stderr)
        return 3

    tn.write(b"export PS1='' PS2=''\n")
    try:
        tn.read_very_eager()
    except Exception:
        pass

    last_code = 0
    for cmd in a.cmds:
        tn.write(f"{cmd}; echo {END}$?\n".encode("utf-8", "replace"))
        idx, m, data = tn.expect([CODE], timeout=a.timeout)
        last_code = int(m.group(1)) if m else -1
        text = data.decode("utf-8", "replace").replace("\r", "")
        # keep only real output: drop any line carrying the marker (echoed command),
        # strip a leading shell prompt if present.
        lines = []
        for ln in text.split("\n"):
            if END in ln or "PS1=" in ln:
                continue
            lines.append(re.sub(r"^:.*?\$ ", "", ln))
        out = "\n".join(lines).strip("\n")
        if out:
            sys.stdout.write(out + "\n")
            sys.stdout.flush()
    try:
        tn.write(b"exit\n")
        tn.close()
    except Exception:
        pass
    return last_code


if __name__ == "__main__":
    sys.exit(main())
