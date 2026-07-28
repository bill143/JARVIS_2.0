"""Tests for the dependency-free feature extraction shared by classifier/similarity."""

from __future__ import annotations

from openjarvis.learning.routing.feature_extraction import (
    DEFAULT_HASH_DIM,
    char_trigrams,
    cosine_similarity,
    feature_dim,
    featurize,
    hashed_ngram_vector,
    tokenize_words,
)


class TestTokenizeWords:
    def test_lowercases_and_splits(self) -> None:
        assert tokenize_words("Hello World") == ["hello", "world"]

    def test_strips_punctuation(self) -> None:
        assert tokenize_words("def foo(): pass") == ["def", "foo", "pass"]

    def test_empty_string(self) -> None:
        assert tokenize_words("") == []


class TestCharTrigrams:
    def test_basic(self) -> None:
        assert char_trigrams("abcd") == ["abc", "bcd"]

    def test_short_text_returns_whole_string(self) -> None:
        assert char_trigrams("ab") == ["ab"]

    def test_empty_returns_empty(self) -> None:
        assert char_trigrams("") == []

    def test_lowercases(self) -> None:
        assert char_trigrams("ABC") == ["abc"]


class TestHashedNgramVector:
    def test_dimension_matches_dim_param(self) -> None:
        vec = hashed_ngram_vector("hello world", dim=32)
        assert len(vec) == 32

    def test_default_dimension(self) -> None:
        vec = hashed_ngram_vector("hello world")
        assert len(vec) == DEFAULT_HASH_DIM

    def test_empty_text_is_zero_vector(self) -> None:
        vec = hashed_ngram_vector("", dim=16)
        assert vec == [0.0] * 16

    def test_l2_normalized(self) -> None:
        vec = hashed_ngram_vector("the quick brown fox jumps", dim=32)
        norm = sum(v * v for v in vec) ** 0.5
        assert abs(norm - 1.0) < 1e-9

    def test_deterministic_across_calls(self) -> None:
        a = hashed_ngram_vector("some repeated query", dim=48)
        b = hashed_ngram_vector("some repeated query", dim=48)
        assert a == b

    def test_different_text_differs(self) -> None:
        a = hashed_ngram_vector("apples and oranges", dim=48)
        b = hashed_ngram_vector("quantum entanglement theory", dim=48)
        assert a != b


class TestFeaturize:
    def test_length_is_hash_dim_plus_engineered(self) -> None:
        vec = featurize("hello", hash_dim=32)
        assert len(vec) == feature_dim(32)
        assert feature_dim(32) == 37

    def test_default_hash_dim(self) -> None:
        vec = featurize("hello")
        assert len(vec) == feature_dim(DEFAULT_HASH_DIM)

    def test_complex_query_has_nonzero_engineered_tail(self) -> None:
        vec = featurize(
            "Explain step by step how gradient descent works, then compare it "
            "to Newton's method",
            hash_dim=32,
        )
        engineered = vec[32:]
        assert any(v > 0 for v in engineered)

    def test_trivial_query_has_near_zero_engineered_tail(self) -> None:
        vec = featurize("Hi", hash_dim=32)
        engineered = vec[32:]
        assert all(v == 0.0 for v in engineered)


class TestCosineSimilarity:
    def test_identical_vectors_similarity_one(self) -> None:
        vec = [0.6, 0.8]
        assert abs(cosine_similarity(vec, vec) - 1.0) < 1e-9

    def test_orthogonal_vectors_zero(self) -> None:
        assert cosine_similarity([1.0, 0.0], [0.0, 1.0]) == 0.0

    def test_opposite_vectors_negative_one(self) -> None:
        assert abs(cosine_similarity([1.0, 0.0], [-1.0, 0.0]) - (-1.0)) < 1e-9

    def test_zero_vector_returns_zero(self) -> None:
        assert cosine_similarity([0.0, 0.0], [1.0, 1.0]) == 0.0

    def test_empty_vectors_return_zero(self) -> None:
        assert cosine_similarity([], []) == 0.0

    def test_mismatched_length_returns_zero(self) -> None:
        assert cosine_similarity([1.0], [1.0, 0.0]) == 0.0
