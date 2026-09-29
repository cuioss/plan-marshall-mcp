"""Record an operator decision (or an adjustment) under an item of a proposal document.

Usage:
  python3 decide.py <proposal.adoc> <item-id> <text>
  python3 decide.py <proposal.adoc> <item-id> --adjust <by> <text>

<item-id> is the item's explicit anchor without brackets (for example z5-12). The line is inserted at the end of
the item's block (the block ends at the next line starting with "[#"), before its "* *Flag*" line if it has one:
  * *Decision*: Operator <YYYY-MM-DD>: <text>
  * *Adjusted by <by>*: <text>
"""
import datetime
import sys


def main(argv):
    if len(argv) < 4:
        print(__doc__)
        return 2
    path, item = argv[1], argv[2]
    if argv[3] == '--adjust':
        if len(argv) < 6:
            print(__doc__)
            return 2
        line = f'* *Adjusted by {argv[4]}*: {argv[5]}\n'
    else:
        line = f'* *Decision*: Operator {datetime.date.today().isoformat()}: {argv[3]}\n'
    text = open(path, encoding='utf-8').read()
    marker = f'[#{item}]\n'
    start = text.find(marker)
    if start < 0:
        print(f'anchor [#{item}] not found in {path}')
        return 1
    end = text.find('\n[#', start + len(marker))
    end = len(text) if end < 0 else end + 1
    block = text[start:end]
    flag = block.find('* *Flag*')
    if flag >= 0:
        block = block[:flag] + line + block[flag:]
    else:
        block = block.rstrip('\n') + '\n' + line + ('\n' if end < len(text) else '')
    open(path, 'w', encoding='utf-8').write(text[:start] + block + text[end:])
    print(f'recorded under [#{item}]')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
