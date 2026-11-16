import unittest

from analyze_library import classify, compact, decode_cue, load_engine


class PreferenceRuleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, cls.patterns, cls.characters = load_engine()

    def categories(self, title):
        return {hit["category"] for hit in classify(title, self.patterns, self.characters)}

    def test_backing_variants_with_fullwidth_and_source_suffixes(self):
        for title in ("曲（Ｉｎｓｔｒｕｍｅｎｔａｌ）", "曲(Off-Vocal)", "曲 オリジナル・カラオケ [ORT]", "曲 [Inst.]", "曲 (Vocal Less)"):
            with self.subTest(title=title):
                self.assertIn("backing", self.categories(title))

    def test_ordinary_title_words_and_standard_versions_are_not_excluded(self):
        for title in ("Instrumental Journey", "曲 (Instrumental Journey)", "Solo Flight", "Off Vocal Collection", "曲 (M@STER VERSION)", "曲 (2023 Version)", "曲 (イルミネーションスターズ Ver.)", "曲 (花海咲季・月村手毬・藤田ことね ver.)"):
            with self.subTest(title=title):
                self.assertEqual(set(), self.categories(title))

    def test_explicit_solo_handles_japanese_and_download_ids(self):
        for title in ("曲 [花海咲季 Solo ver.]_44851725", "曲 最上静香 ソロ・バージョン [96kHz_24bit]", "曲（天海春香ソロ・リミックス）", "曲 (Solo Remix)"):
            with self.subTest(title=title):
                self.assertIn("solo-explicit", self.categories(title))

    def test_named_character_requires_exact_single_known_identity(self):
        self.assertIn("solo-named", self.categories("曲（黛 冬優子Ｖｅｒ．）"))
        self.assertIn("solo-named", self.categories("曲 [櫻木真乃 Ver.]"))
        self.assertNotIn("solo-named", self.categories("曲 (櫻木真乃・風野灯織 Ver.)"))
        self.assertNotIn("solo-named", self.categories("曲 (未知の人 Ver.)"))

    def test_repeated_filename_album_prefix_does_not_mask_final_marker(self):
        self.assertIn("backing", self.categories("OFF VOCAL COLLECTION_01_曲 (Off Vocal)"))
        self.assertNotIn("backing", self.categories("OFF VOCAL COLLECTION_01_通常曲"))

    def test_optional_categories_remain_distinct(self):
        self.assertEqual({"short"}, self.categories("曲 (Game Size)"))
        self.assertEqual({"piano"}, self.categories("曲 (Piano Arrange Version)"))

    def test_normalization_does_not_need_fuzzy_person_matching(self):
        self.assertEqual(compact("黛 冬優子"), compact("黛　冬優子"))
        self.assertNotEqual(compact("篠澤 広"), compact("條澤 広"))

    def test_cue_decoding_preserves_japanese_titles(self):
        text = 'TITLE "曲名"\n'
        for encoding in ("utf-8-sig", "utf-16", "cp932"):
            with self.subTest(encoding=encoding):
                self.assertEqual(text, decode_cue(text.encode(encoding))[0])


if __name__ == "__main__":
    unittest.main()
