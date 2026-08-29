package com.aditya.rag.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.transformer.splitter.TextSplitter;

/**
 * A recursive character text splitter modelled on the LangChain
 * {@code RecursiveCharacterTextSplitter} algorithm.
 *
 * <p>The idea is to keep semantically related text together for as long as
 * possible. It tries to split on a prioritized list of separators (paragraph
 * breaks first, then line breaks, then spaces, then raw characters). Whenever a
 * piece is still larger than {@code chunkSize}, it recurses using the next
 * separator in the list. Finally, small neighbouring pieces are merged back
 * together up to {@code chunkSize} with {@code chunkOverlap} characters carried
 * over between consecutive chunks to preserve context.</p>
 */
public class RecursiveCharacterTextSplitter extends TextSplitter {

    /** Default separators, ordered from most to least semantically meaningful. */
    private static final List<String> DEFAULT_SEPARATORS = List.of("\n\n", "\n", " ", "");

    private final int chunkSize;
    private final int chunkOverlap;
    private final List<String> separators;

    public RecursiveCharacterTextSplitter(int chunkSize, int chunkOverlap) {
        this(chunkSize, chunkOverlap, DEFAULT_SEPARATORS);
    }

    public RecursiveCharacterTextSplitter(int chunkSize, int chunkOverlap, List<String> separators) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be greater than 0");
        }
        if (chunkOverlap < 0) {
            throw new IllegalArgumentException("chunkOverlap must not be negative");
        }
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be smaller than chunkSize");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.separators = separators;
    }

    /**
     * Entry point used by Spring AI's {@link TextSplitter} to split each
     * document's text into chunk strings.
     */
    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return recursiveSplit(text, separators);
    }

    private List<String> recursiveSplit(String text, List<String> currentSeparators) {
        List<String> finalChunks = new ArrayList<>();

        // Pick the first separator that actually occurs in the text; fall back
        // to the last (usually "") which splits character-by-character.
        String separator = currentSeparators.get(currentSeparators.size() - 1);
        List<String> remainingSeparators = List.of();
        for (int i = 0; i < currentSeparators.size(); i++) {
            String candidate = currentSeparators.get(i);
            if (candidate.isEmpty() || text.contains(candidate)) {
                separator = candidate;
                remainingSeparators = currentSeparators.subList(i + 1, currentSeparators.size());
                break;
            }
        }

        List<String> splits = splitOn(text, separator);

        // Buffer of pieces that are small enough to be merged together later.
        List<String> goodSplits = new ArrayList<>();
        for (String piece : splits) {
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.length() <= chunkSize) {
                goodSplits.add(piece);
            } else {
                // Flush what we have buffered, then break the oversized piece
                // down further with the next separator.
                if (!goodSplits.isEmpty()) {
                    finalChunks.addAll(mergeSplits(goodSplits, separator));
                    goodSplits.clear();
                }
                if (remainingSeparators.isEmpty()) {
                    // No smaller separator left: hard-split by chunkSize.
                    finalChunks.addAll(hardSplit(piece));
                } else {
                    finalChunks.addAll(recursiveSplit(piece, remainingSeparators));
                }
            }
        }
        if (!goodSplits.isEmpty()) {
            finalChunks.addAll(mergeSplits(goodSplits, separator));
        }
        return finalChunks;
    }

    /**
     * Splits {@code text} on {@code separator}, keeping the separator attached
     * so re-joined chunks read naturally. An empty separator splits into
     * individual characters.
     */
    private List<String> splitOn(String text, String separator) {
        List<String> result = new ArrayList<>();
        if (separator.isEmpty()) {
            for (int i = 0; i < text.length(); i++) {
                result.add(String.valueOf(text.charAt(i)));
            }
            return result;
        }
        int start = 0;
        int index;
        while ((index = text.indexOf(separator, start)) != -1) {
            result.add(text.substring(start, index + separator.length()));
            start = index + separator.length();
        }
        if (start < text.length()) {
            result.add(text.substring(start));
        }
        return result;
    }

    /**
     * Greedily merges consecutive pieces into chunks no larger than
     * {@code chunkSize}, carrying {@code chunkOverlap} characters from the end
     * of one chunk into the start of the next.
     */
    private List<String> mergeSplits(List<String> splits, String separator) {
        List<String> chunks = new ArrayList<>();
        List<String> currentWindow = new ArrayList<>();
        int currentLength = 0;

        for (String piece : splits) {
            int pieceLength = piece.length();
            if (currentLength + pieceLength > chunkSize && !currentWindow.isEmpty()) {
                String chunk = joinChunk(currentWindow);
                if (!chunk.isBlank()) {
                    chunks.add(chunk);
                }
                // Drop pieces from the front until we are back under the
                // overlap budget, so the next chunk reuses trailing context.
                while (currentLength > chunkOverlap && !currentWindow.isEmpty()) {
                    currentLength -= currentWindow.remove(0).length();
                }
            }
            currentWindow.add(piece);
            currentLength += pieceLength;
        }
        String lastChunk = joinChunk(currentWindow);
        if (!lastChunk.isBlank()) {
            chunks.add(lastChunk);
        }
        return chunks;
    }

    private String joinChunk(List<String> pieces) {
        return String.join("", pieces).trim();
    }

    /** Last-resort split for a piece with no usable separator. */
    private List<String> hardSplit(String text) {
        List<String> chunks = new ArrayList<>();
        int step = chunkSize - chunkOverlap;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end == text.length()) {
                break;
            }
        }
        return chunks;
    }
}
