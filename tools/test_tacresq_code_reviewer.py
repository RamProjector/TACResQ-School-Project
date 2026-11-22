from pathlib import Path
import importlib.util
import sys
import unittest
SPEC=importlib.util.spec_from_file_location("reviewer","tools/tacresq_code_reviewer.py")
reviewer=importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name]=reviewer
SPEC.loader.exec_module(reviewer)
class AddedLineParserTests(unittest.TestCase):
 def test_tracks_added_line_numbers_and_ignores_removed_lines(self):
  patch="@@ -4,2 +4,3 @@\n old_line\n-removed()\n+added_one()\n+added_two()\n"
  self.assertEqual(list(reviewer.added_lines(patch)),[(5,"added_one()"),(6,"added_two()")])
 def test_critical_service_role_pattern_is_detected(self):
  secret_value="sb"+"_secret_"+"x"*20
  findings=reviewer.review_file("app/src/main/java/Example.kt",'+val key = "'+secret_value+'"\n')
  self.assertTrue(any(f.severity=="CRITICAL" and f.rule=="SUPABASE_SERVICE_ROLE" for f in findings))
 def test_server_environment_variable_name_is_not_a_secret_value(self):
  env_name="SUPABASE_"+"SERVICE_ROLE_KEY"
  findings=reviewer.review_file("supabase/functions/tacresq-notify/index.ts",'+const key = Deno.env.get("'+env_name+'");\n')
  self.assertFalse(any(f.rule=="SUPABASE_SERVICE_ROLE" for f in findings))
 def test_pem_delimiter_regex_is_not_a_private_key(self):
  begin="-"*5+"BEGIN PRIVATE KEY"+"-"*5
  end="-"*5+"END PRIVATE KEY"+"-"*5
  source_line="const key = value.replace(/"+begin+"|"+end+'/g, "");'
  findings=reviewer.review_file("supabase/functions/tacresq-notify/index.ts","+"+source_line+"\n")
  self.assertFalse(any(f.rule=="PRIVATE_KEY" for f in findings))
 def test_publishable_key_identifier_is_not_flagged_as_service_role(self):
  findings=reviewer.review_file("app/src/main/java/Example.kt",'+const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_demo"\n')
  self.assertFalse(any(f.rule=="SUPABASE_SERVICE_ROLE" for f in findings))
 def test_private_key_header_is_critical(self):
  findings=reviewer.review_file("secrets.txt","+-----BEGIN PRIVATE KEY-----\n")
  self.assertTrue(any(f.rule=="PRIVATE_KEY" and f.severity=="CRITICAL" for f in findings))
 def test_debug_configuration_is_reported(self):
  findings=reviewer.review_file("backend/compose.yaml",'+APP_DEBUG: "true"\n')
  self.assertTrue(any(f.rule=="DISABLE_APP_DEBUG" for f in findings))
 def test_step_summary_writer_uses_real_newline(self):
  source=Path(__file__).with_name("tacresq_code_reviewer.py").read_text(encoding="utf-8")
  self.assertIn(r'output.write(report+"\n")', source)
 def test_binary_or_lock_files_are_skipped(self):
  self.assertEqual(reviewer.review_file("assets/image.png","+anything"),[])
if __name__=="__main__":unittest.main()
