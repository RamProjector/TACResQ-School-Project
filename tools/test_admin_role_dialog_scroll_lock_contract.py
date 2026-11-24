from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/web/src/pages/AdminConsole.tsx").read_text(encoding="utf-8")


def test_role_change_dialog_locks_and_restores_background_scroll():
    effect = SOURCE.split("useEffect(() => {\n    if (!target) return;", 1)[1].split("}, [target, saving]);", 1)[0]
    assert "const previousBodyOverflow = document.body.style.overflow;" in effect
    assert 'document.body.style.overflow = "hidden";' in effect
    assert "document.body.style.overflow = previousBodyOverflow;" in effect
    assert effect.index('document.body.style.overflow = "hidden";') < effect.index("document.addEventListener(\"keydown\", onKeyDown);")
    assert effect.index("document.removeEventListener(\"keydown\", onKeyDown);") < effect.index("document.body.style.overflow = previousBodyOverflow;")


def test_role_change_dialog_keeps_existing_keyboard_focus_management():
    assert 'role="dialog" aria-modal="true"' in SOURCE
    assert 'if (event.key === "Escape" && !saving)' in SOURCE
    assert 'if (event.shiftKey && document.activeElement === first)' in SOURCE
    assert 'if (trigger?.isConnected) trigger.focus();' in SOURCE


if __name__ == "__main__":
    test_role_change_dialog_locks_and_restores_background_scroll()
    test_role_change_dialog_keeps_existing_keyboard_focus_management()
    print("Admin role dialog scroll-lock contract passed.")
