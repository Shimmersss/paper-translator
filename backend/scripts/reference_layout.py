import re

REFERENCE_NUMBER_RE = re.compile(
    r"^\s*(?:\[(\d{1,4})\]|\((\d{1,4})\)|(\d{1,4})[.)])\s*"
)
REFERENCE_YEAR_RE = re.compile(r"\b(?:18|19|20)\d{2}[a-z]?\b", re.IGNORECASE)
REFERENCE_SIGNAL_RE = re.compile(
    r"\b(?:doi|https?://|et\s+al\.?|vol\.?|no\.?|pp?\.?|journal|proceedings|conference|"
    r"publisher|press|isbn|arxiv)\b",
    re.IGNORECASE,
)
REFERENCE_PUBLICATION_RE = re.compile(
    r"\b(?:doi|https?://|vol\.?|no\.?|pp?\.?|journal|proceedings|conference|"
    r"publisher|press|isbn|arxiv)\b",
    re.IGNORECASE,
)
REFERENCE_HEADING_RE = re.compile(
    # A singular "Reference" is frequently a table-column label.  It must
    # not start the bibliography section and preserve all following pages.
    r"^\s*(?:references|bibliography|works\s+cited|literature\s+cited)\s*$",
    re.IGNORECASE,
)
# Headings of sections that journals place after the bibliography. Appendix
# headings usually carry an identifier and a title ("Appendix A. Derivation",
# "APPENDIX B: Proofs"); requiring a bare "Appendix" left whole appendices
# untranslated. Only the keywords are case-insensitive so that ordinary prose
# such as "Appendix is given" does not end the section.
_SECTION_NUMBER = r"(?:(?:[A-Z]|\d{1,2}(?:\.\d{1,2})*)[.)]?\s+)?"
_APPENDIX_HEADING = (
    r"(?i:appendix|appendices)"
    r"(?:\s+(?:[A-Z]|[IVX]{1,4}|\d{1,2})(?:\.\d{1,2})*)?"
    r"(?:\s*[.:\uff1a\u2014\u2013-]\s*\S.*|\s+[A-Z(].*)?"
)
_POST_REFERENCE_HEADING = (
    r"(?:(?i:supplementary|supplemental|supporting)\s+(?i:materials?|information|data)"
    r"|(?i:online\s+)?(?i:methods)|(?i:extended\s+data)"
    r"|(?i:acknowledge?ments?)|(?i:author\s+contributions))"
    r"\s*[.:\uff1a]?"
)
REFERENCE_SECTION_END_RE = re.compile(
    rf"^\s*{_SECTION_NUMBER}(?:{_APPENDIX_HEADING}|{_POST_REFERENCE_HEADING})\s*$"
)
REFERENCE_AUTHOR_RE = re.compile(
    r"^\s*(?:[A-Z][A-Za-z'’-]+,\s*(?:[A-Z]\.?\s*){1,3}|"
    r"[A-Z][A-Za-z'’-]+\s+(?:et\s+al\.?|and|&)\s+)",
)
NUMBERED_REFERENCE_AUTHOR_RE = re.compile(
    r"^\s*(?:[A-Z][A-Za-z'’-]+,\s*|(?:[A-Z]\.\s*)+[A-Z][A-Za-z'’-]+)",
)


def reference_number(text):
    match = REFERENCE_NUMBER_RE.match(text or "")
    if not match:
        return None
    return int(next(value for value in match.groups() if value is not None))


def is_numbered_reference(text):
    """Keep numbered lists untouched unless their content looks bibliographic."""
    if reference_number(text) is None:
        return False
    content = REFERENCE_NUMBER_RE.sub("", text or "", count=1)
    return bool(
        REFERENCE_SIGNAL_RE.search(content)
        or (
            REFERENCE_YEAR_RE.search(content)
            and NUMBERED_REFERENCE_AUTHOR_RE.search(content)
        )
    )


def is_reference_paragraph(text):
    """Identify bibliography headings and entries that must remain untranslated."""
    text = (text or "").strip()
    if not text:
        return False
    if is_reference_heading(text):
        return True
    if is_numbered_reference(text):
        return True
    return bool(
        REFERENCE_YEAR_RE.search(text)
        and REFERENCE_PUBLICATION_RE.search(text)
        and REFERENCE_AUTHOR_RE.search(text)
    )


def is_reference_heading(text):
    return bool(REFERENCE_HEADING_RE.fullmatch((text or "").strip()))


def reference_section_flags(texts, in_reference_section=False):
    """Mark bibliography paragraphs, including weak entries after a heading."""
    flags = []
    for text in texts:
        normalized = (text or "").strip()
        if is_reference_heading(normalized):
            in_reference_section = True
        elif in_reference_section and REFERENCE_SECTION_END_RE.fullmatch(normalized):
            in_reference_section = False
        flags.append(in_reference_section or is_reference_paragraph(normalized))
    return flags


def reference_split_points(lines):
    numbered_lines = [
        (index, reference_number(text))
        for index, text in enumerate(lines)
        if reference_number(text) is not None
    ]
    numbers = [number for _, number in numbered_lines]
    has_sequence = any(
        current == previous + 1 for previous, current in zip(numbers, numbers[1:])
    )
    has_bibliographic_signal = any(is_numbered_reference(text) for text in lines)
    if len(numbered_lines) < 2 or not has_sequence or not has_bibliographic_signal:
        return []
    return [index for index, _ in numbered_lines]
