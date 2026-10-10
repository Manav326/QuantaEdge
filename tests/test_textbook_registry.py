import unittest

from scripts.textbook_registry import host_allowed, parse_ncert_catalog, parse_scert_classes, parse_scert_medium

NCERT_FIXTURE = r"""
<script>
function change()
{
    //this function check the classthat you have selected
    if (document.test.tclass.value==6)
    {
        document.test.tsubject.options[0].text="..Select Subject..";
        document.test.tsubject.options[1].text="Mathematics";
    }
}
function change1(sind)
{
    document.test.tbook.options[0].text="..Select Book Title..";
    if((document.test.tclass.value==6) && (document.test.tsubject.options[sind].text=="Mathematics"))
    {
        document.test.tbook.options[1].text="गणित प्रकाश";
        document.test.tbook.options[1].value="textbook.php?fhgp1=0-14"
        document.test.tbook.options[2].text="Ganita Prakash";
        document.test.tbook.options[2].value="textbook.php?fegp1=0-14"
    }
}
</script>
"""


class TextbookRegistryTests(unittest.TestCase):
    def test_official_host_gate_requires_https(self):
        self.assertTrue(host_allowed("https://ncert.nic.in/textbook.php"))
        self.assertTrue(host_allowed("https://scert.bihar.gov.in/eresources"))
        self.assertFalse(host_allowed("http://ncert.nic.in/textbook.php"))
        self.assertFalse(host_allowed("https://untrusted.example/book.pdf"))
        self.assertFalse(host_allowed("https://user:pass@ncert.nic.in/book.pdf"))

    def test_ncert_catalogue_splits_english_and_hindi(self):
        books = parse_ncert_catalog(NCERT_FIXTURE)
        self.assertEqual(2, len(books))
        by_medium = {item["medium"]: item for item in books}
        self.assertEqual("fhgp1", by_medium["hindi"]["code"])
        self.assertEqual("fegp1", by_medium["english"]["code"])
        self.assertEqual(6, int(by_medium["english"]["class"]))
        self.assertEqual(14, by_medium["english"]["chapter_count"])

    def test_scert_class_detection_handles_roman_and_numeric(self):
        self.assertEqual([6, 7, 8], parse_scert_classes("Class : CLASS VI, ,CLASS VII, ,CLASS VIII,"))
        self.assertEqual([9, 10, 11, 12], parse_scert_classes("Class IX, Class X, Class 11, Class XII"))

    def test_scert_language_detection_handles_bilingual_books(self):
        self.assertEqual("hindi", parse_scert_medium("language : Hindi"))
        self.assertEqual("english", parse_scert_medium("language : English"))
        self.assertEqual("both", parse_scert_medium("language : Hindi English"))
        self.assertIsNone(parse_scert_medium("language : Urdu"))


if __name__ == "__main__":
    unittest.main()
