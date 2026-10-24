import unittest
from tools.tacresq_frontend_reviewer import review_file, parse_added_lines

class FrontendReviewerTests(unittest.TestCase):
    def test_added_line_numbers(self):
        patch = "@@ -1,2 +1,3 @@\n old\n-removed\n+newOne\n+newTwo\n"
        self.assertEqual(list(parse_added_lines(patch)), [(2, "newOne"), (3, "newTwo")])

    def test_clickable_without_accessibility_hint_is_reported(self):
        patch = "@@ -1 +1 @@\n+Modifier.clickable { onTap() }\n"
        findings = review_file("app/src/main/java/com/tacresq/app/ui/Example.kt", patch)
        self.assertTrue(any(f.category == "ACCESSIBILITY" for f in findings))

    def test_accessible_clickable_not_flagged_for_missing_semantics(self):
        patch = "@@ -1,2 +1,2 @@\n+Modifier.clickable { onTap() }\n+    .semantics { contentDescription = \"Open map\" }\n"
        findings = review_file("app/src/main/java/com/tacresq/app/ui/Example.kt", patch)
        self.assertFalse(any(f.category == "ACCESSIBILITY" for f in findings))

    def test_gps_screen_requires_device_check(self):
        findings = review_file("app/src/main/java/com/tacresq/app/ui/MapScreen.kt", "@@ -1 +1 @@\n+Text(\"Map\")\n")
        self.assertTrue(any(f.category == "GPS_DEVICE_CHECK" and f.severity == "MANUAL" for f in findings))

    def test_backend_or_docs_files_are_not_frontend_scanned(self):
        self.assertEqual(review_file("supabase/migrations/example.sql", "+select true"), [])

if __name__ == "__main__":
    unittest.main()
