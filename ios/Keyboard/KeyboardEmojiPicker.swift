import UIKit

final class KeyboardEmojiPicker: UIView {
    private struct Catalog: Decodable {
        let order: [String]
        let groups: [String: [String]]
        let search: [String: String]?
    }

    private static let catalog: Catalog = {
        guard let url = Bundle(for: KeyboardEmojiPicker.self).url(forResource: "emoji", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let catalog = try? JSONDecoder().decode(Catalog.self, from: data) else {
            return Catalog(order: [], groups: [:], search: nil)
        }
        return catalog
    }()
    private static let recentsKey = "mw.emoji.recent.v1"

    static var recentEmoji: [String] {
        UserDefaults.standard.stringArray(forKey: recentsKey) ?? []
    }

    /// Moves `emoji` to the front of the shared recents; returns the new list.
    @discardableResult
    static func remember(_ emoji: String) -> [String] {
        let updated = Array(([emoji] + recentEmoji.filter { $0 != emoji }).prefix(30))
        UserDefaults.standard.set(updated, forKey: recentsKey)
        return updated
    }

    /// Apple shows recents before the first search letter; fall back to the first smileys.
    static var searchSuggestions: [String] {
        let recents = recentEmoji
        return recents.isEmpty ? Array(catalog.groups[catalog.order.first ?? "", default: []].prefix(24)) : recents
    }
    private static let categoryIcons = [
        "Recent": "clock",
        "Smileys & Emotion": "face.smiling",
        "People & Body": "hand.wave",
        "Animals & Nature": "leaf",
        "Food & Drink": "fork.knife",
        "Travel & Places": "car",
        "Activities": "soccerball",
        "Objects": "lightbulb",
        "Symbols": "heart",
        "Flags": "flag",
    ]

    private static let recentCategory = "Recent"

    private let tabs = UIScrollView()
    private let heading = UILabel()
    private let searchBar = UIButton(type: .system)
    private let gridLayout = UICollectionViewFlowLayout()
    private lazy var grid = UICollectionView(frame: .zero, collectionViewLayout: gridLayout)
    private let accent: UIColor
    private let ink: UIColor
    private let footerHeight: CGFloat
    private let letters: UIButton
    private let delete: UIButton
    private let globe: UIButton?
    private let onInsert: (String) -> Void
    private let onSearch: () -> Void
    private let categories = [KeyboardEmojiPicker.recentCategory] + KeyboardEmojiPicker.catalog.order
    private var recents = KeyboardEmojiPicker.recentEmoji
    private var categoryButtons: [UIButton] = []
    private var selectedSection: Int?
    private var initialSection: Int?

    init(
        frame: CGRect, accent: UIColor, ink: UIColor, footerHeight: CGFloat,
        letters: UIButton, delete: UIButton, globe: UIButton?,
        onSearch: @escaping () -> Void, onInsert: @escaping (String) -> Void
    ) {
        self.accent = accent
        self.ink = ink
        self.footerHeight = footerHeight
        self.letters = letters
        self.delete = delete
        self.globe = globe
        self.onInsert = onInsert
        self.onSearch = onSearch
        super.init(frame: frame)

        heading.font = .systemFont(ofSize: 13, weight: .semibold)
        heading.textColor = ink.withAlphaComponent(0.7)
        heading.accessibilityTraits = .header
        addSubview(heading)

        var searchStyle = UIButton.Configuration.plain()
        searchStyle.image = UIImage(systemName: "magnifyingglass")
        searchStyle.imagePadding = 9
        searchStyle.contentInsets = .init(top: 0, leading: 12, bottom: 0, trailing: 42)
        searchStyle.title = "Search Emoji"
        searchStyle.baseForegroundColor = ink.withAlphaComponent(0.75)
        searchBar.configuration = searchStyle
        searchBar.contentHorizontalAlignment = .leading
        searchBar.backgroundColor = ink.withAlphaComponent(0.12)
        searchBar.layer.cornerCurve = .continuous
        searchBar.accessibilityLabel = "Search Emoji"
        searchBar.addAction(UIAction { [weak self] _ in self?.onSearch() }, for: .touchUpInside)
        addSubview(searchBar)


        gridLayout.scrollDirection = .horizontal
        gridLayout.minimumInteritemSpacing = 4
        gridLayout.minimumLineSpacing = 3
        gridLayout.sectionInset = UIEdgeInsets(top: 0, left: 4, bottom: 0, right: 9)
        grid.register(KeyboardEmojiCell.self, forCellWithReuseIdentifier: "emoji")
        grid.dataSource = self
        grid.delegate = self
        grid.backgroundColor = .clear
        grid.showsHorizontalScrollIndicator = false
        grid.contentInsetAdjustmentBehavior = .never
        grid.accessibilityLabel = "Emoji picker"
        addSubview(grid)

        tabs.showsHorizontalScrollIndicator = false
        tabs.contentInsetAdjustmentBehavior = .never
        addSubview(tabs)
        addSubview(letters)
        addSubview(delete)
        if let globe { addSubview(globe) }

        for (section, category) in categories.enumerated() {
            let button = UIButton(type: .custom)
            let symbol = Self.categoryIcons[category] ?? "circle"
            button.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 17, weight: .medium)), for: .normal)
            button.tintColor = ink
            button.accessibilityLabel = category == Self.recentCategory ? "Recently used" : category
            button.addAction(UIAction { [weak self] _ in self?.jumpToSection(section) }, for: .touchUpInside)
            button.layer.cornerRadius = 10
            tabs.addSubview(button)
            categoryButtons.append(button)
        }
        let first = recents.isEmpty && categories.count > 1 ? 1 : 0
        initialSection = first
        highlightSection(first)
    }

    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        let width = bounds.width
        guard width > 0 else { return }
        let searchHeight: CGFloat = footerHeight < 40 ? 28 : 38
        let categoryHeight: CGFloat = 22
        let gridTop = searchHeight + categoryHeight + 3
        let footerY = bounds.height - footerHeight
        let gridBottom = footerY
        let gridHeight = max(1, gridBottom - gridTop - 6)
        searchBar.frame = CGRect(x: 4, y: 0, width: width - 8, height: searchHeight)
        searchBar.layer.cornerRadius = searchHeight / 2
        heading.frame = CGRect(x: 8, y: searchHeight, width: width - 16, height: categoryHeight)
        grid.frame = CGRect(x: 0, y: gridTop, width: width, height: gridHeight)

        let keyWidth: CGFloat = 44
        letters.frame = CGRect(x: 0, y: footerY, width: keyWidth, height: footerHeight)
        delete.frame = CGRect(x: width - keyWidth, y: footerY, width: keyWidth, height: footerHeight)
        globe?.frame = CGRect(x: letters.frame.maxX + 4, y: footerY, width: 37, height: footerHeight)
        let tabsLeading = (globe?.frame.maxX ?? letters.frame.maxX) + 4
        tabs.frame = CGRect(x: tabsLeading, y: footerY, width: max(0, delete.frame.minX - tabsLeading - 4), height: footerHeight)
        let tabWidth = max(27, tabs.bounds.width / CGFloat(max(1, categoryButtons.count)))
        for (index, button) in categoryButtons.enumerated() {
            button.frame = CGRect(x: CGFloat(index) * tabWidth + 1, y: 2, width: tabWidth - 2, height: footerHeight - 4)
        }
        tabs.contentSize = CGSize(width: CGFloat(categoryButtons.count) * tabWidth, height: footerHeight)

        let rows = gridHeight >= 150 ? 4 : max(1, Int((gridHeight + 4) / 33))
        let cellHeight = floor((gridHeight - CGFloat(rows - 1) * 4) / CGFloat(rows))
        let cellWidth = min(57, max(41, floor((width - 12) / 7)))
        if cellHeight > 0 {
            let size = CGSize(width: cellWidth, height: cellHeight)
            if gridLayout.itemSize != size { gridLayout.itemSize = size }
        }
        if let initialSection {
            self.initialSection = nil
            jumpToSection(initialSection)
        }
        revealSelectedTab()
    }

    // shortcut: CLDR keywords can't rank relevance ("dog" also tags bone and hot dog), so common words
    // get a hand-picked emoji; replace with frequency data if suggestions need to get smarter.
    private static let topPicks: [String: String] = [
        "heart": "❤️", "corazon": "❤️", "love": "❤️", "amor": "❤️", "kiss": "😘", "beso": "😘",
        "happy": "😀", "feliz": "😀", "smile": "😊", "sonrisa": "😊", "laugh": "😂", "risa": "😂",
        "sad": "😢", "triste": "😢", "cool": "😎", "thanks": "🙏", "gracias": "🙏", "yes": "👍",
        "dog": "🐶", "perro": "🐶", "cat": "🐱", "gato": "🐱", "fire": "🔥", "fuego": "🔥",
        "sun": "☀️", "sol": "☀️", "moon": "🌙", "luna": "🌙", "star": "⭐", "estrella": "⭐",
        "rain": "🌧️", "lluvia": "🌧️", "car": "🚗", "coche": "🚗", "house": "🏠", "casa": "🏠",
        "pizza": "🍕", "beer": "🍺", "cerveza": "🍺", "coffee": "☕", "cafe": "☕",
        "party": "🎉", "fiesta": "🎉", "music": "🎵", "musica": "🎵", "money": "💰", "dinero": "💰",
        "cake": "🎂", "tarta": "🎂", "birthday": "🎂", "cumpleanos": "🎂", "christmas": "🎄", "navidad": "🎄",
    ]

    /// Keyword -> best emoji: exact name first, then names containing the word, then plain keywords.
    private static let keywordIndex: [String: String] = {
        guard let search = catalog.search else { return [:] }
        var best: [String: (rank: Int, emoji: String)] = [:]
        var position = 0
        for emoji in catalog.order.flatMap({ catalog.groups[$0, default: []] }) {
            position += 1
            let key = emoji.replacingOccurrences(of: "\u{FE0F}", with: "")
            guard let annotation = search[key] else { continue }
            let segments = annotation.split(separator: "|").map { normalized(String($0)) }
            // The first two segments are the Spanish and English names when CLDR has them.
            let names = segments.prefix(2)
            for word in Set(segments) where word.count >= 3 && word.allSatisfy(\.isLetter) {
                var tier = 2
                if names.contains(word) {
                    tier = 0
                } else if names.contains(where: { name in name.split(separator: " ").contains(Substring(word)) }) {
                    tier = 1
                }
                let rank = tier * 10_000 + position
                if rank < best[word]?.rank ?? .max { best[word] = (rank, emoji) }
            }
        }
        return best.mapValues(\.emoji)
    }()

    private static func normalized(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespaces).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }

    /// Emoji the suggestion strip can offer for a typed word, as the system keyboard does.
    static func suggestion(for word: String) -> String? {
        let key = normalized(word)
        return topPicks[key] ?? keywordIndex[key]
    }

    static func searchEmoji(matching query: String) -> [String] {
        let words = query.split(whereSeparator: \.isWhitespace)
        guard !words.isEmpty, let search = catalog.search else { return [] }
        return catalog.order.lazy.flatMap { catalog.groups[$0, default: []] }.filter { emoji in
            let key = emoji.replacingOccurrences(of: "\u{FE0F}", with: "")
            guard let annotation = search[key] else { return false }
            return words.allSatisfy { annotation.localizedStandardContains(String($0)) }
        }.prefix(160).map { $0 }
    }

    private func jumpToSection(_ section: Int) {
        grid.layoutIfNeeded()
        guard let first = gridLayout.layoutAttributesForItem(at: IndexPath(item: 0, section: section)) else { return }
        let sectionStart = first.frame.minX - gridLayout.sectionInset.left
        let lastOffset = max(0, grid.contentSize.width - grid.bounds.width)
        grid.setContentOffset(CGPoint(x: min(max(0, sectionStart), lastOffset), y: 0), animated: false)
        highlightSection(section)
    }

    private func highlightSection(_ section: Int) {
        guard categories.indices.contains(section), selectedSection != section else { return }
        selectedSection = section
        heading.text = categoryButtons[section].accessibilityLabel
        for (index, button) in categoryButtons.enumerated() {
            let selected = section == index
            button.backgroundColor = selected ? accent.withAlphaComponent(0.2) : .clear
            button.tintColor = selected ? accent : ink.withAlphaComponent(0.8)
            button.accessibilityTraits = selected ? [.button, .selected] : .button
        }
        revealSelectedTab()
    }

    private func revealSelectedTab() {
        guard let selectedSection else { return }
        tabs.scrollRectToVisible(categoryButtons[selectedSection].frame, animated: false)
    }

    private func values(in section: Int) -> [String] {
        section == 0 ? recents : Self.catalog.groups[categories[section], default: []]
    }

    private var leadingItem: IndexPath? {
        let edge = grid.contentOffset.x
        return gridLayout.layoutAttributesForElements(in: grid.bounds)?
            .filter { $0.representedElementCategory == .cell && $0.frame.maxX > edge }
            .map(\.indexPath)
            .min()
    }
}

extension KeyboardEmojiPicker: UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    func numberOfSections(in collectionView: UICollectionView) -> Int { categories.count }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        section == 0 ? max(1, values(in: section).count) : values(in: section).count
    }

    func collectionView(
        _ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
        sizeForItemAt indexPath: IndexPath
    ) -> CGSize {
        if indexPath.section == 0, values(in: 0).isEmpty {
            return CGSize(width: 142, height: max(1, grid.bounds.height))
        }
        return gridLayout.itemSize
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard scrollView === grid, let item = leadingItem else { return }
        highlightSection(item.section)
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "emoji", for: indexPath)
        if let cell = cell as? KeyboardEmojiCell {
            let values = values(in: indexPath.section)
            let value = values.indices.contains(indexPath.item) ? values[indexPath.item] : nil
            cell.configure(emoji: value, emptyMessage: "No recent emoji", ink: ink)
        }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        collectionView.deselectItem(at: indexPath, animated: false)
        let values = values(in: indexPath.section)
        guard values.indices.contains(indexPath.item) else { return }
        let value = values[indexPath.item]
        let updated = Self.remember(value)
        if updated != recents {
            let anchor = leadingItem
            let previousOffset = grid.contentOffset.x
            let previousX = anchor.flatMap { gridLayout.layoutAttributesForItem(at: $0)?.frame.minX }
            recents = updated
            grid.reloadData()
            grid.layoutIfNeeded()
            if let anchor, let previousX, let currentX = gridLayout.layoutAttributesForItem(at: anchor)?.frame.minX {
                let edge = previousOffset + currentX - previousX
                let lastOffset = max(0, grid.contentSize.width - grid.bounds.width)
                grid.setContentOffset(CGPoint(x: min(max(0, edge), lastOffset), y: 0), animated: false)
            }
        }
        onInsert(value)
    }
}

private final class KeyboardEmojiCell: UICollectionViewCell {
    private let label = UILabel()
    private var showsEmoji = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        label.frame = contentView.bounds
        label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        label.textAlignment = .center
        label.isAccessibilityElement = false
        contentView.addSubview(label)
        isAccessibilityElement = true
    }

    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        label.font = .systemFont(ofSize: showsEmoji ? min(35, bounds.height * 0.76) : 13)
    }

    func configure(emoji: String?, emptyMessage: String, ink: UIColor) {
        showsEmoji = emoji != nil
        label.text = emoji ?? emptyMessage
        label.textColor = ink.withAlphaComponent(showsEmoji ? 1 : 0.7)
        accessibilityLabel = label.text
        accessibilityTraits = showsEmoji ? .button : .staticText
        setNeedsLayout()
    }
}

/// Apple's emoji search header: a field with the typed query and a caret,
/// then one horizontally scrolling row of results above the normal keys.
final class KeyboardEmojiSearchBar: UIView {
    private let field = UIView()
    private let icon = UIImageView(image: UIImage(systemName: "magnifyingglass"))
    private let text = UILabel()
    private let caret = UIView()
    private let clear = UIButton(type: .system)
    private let results = UIScrollView()
    private let empty = UILabel()
    private var buttons: [UIButton] = []
    private let ink: UIColor
    private let fieldHeight: CGFloat
    private let onClear: () -> Void
    private let onPick: (String) -> Void

    init(frame: CGRect, ink: UIColor, accent: UIColor, fieldHeight: CGFloat,
         onClear: @escaping () -> Void, onPick: @escaping (String) -> Void) {
        self.ink = ink
        self.fieldHeight = fieldHeight
        self.onClear = onClear
        self.onPick = onPick
        super.init(frame: frame)

        field.backgroundColor = ink.withAlphaComponent(0.12)
        field.layer.cornerCurve = .continuous
        field.isAccessibilityElement = true
        field.accessibilityTraits = .searchField
        field.accessibilityLabel = "Search Emoji"
        addSubview(field)
        icon.tintColor = ink.withAlphaComponent(0.6)
        icon.contentMode = .scaleAspectFit
        field.addSubview(icon)
        text.font = .systemFont(ofSize: 17)
        field.addSubview(text)
        caret.backgroundColor = accent
        caret.layer.cornerRadius = 1
        field.addSubview(caret)
        let blink = CABasicAnimation(keyPath: "opacity")
        blink.fromValue = 1
        blink.toValue = 0
        blink.duration = 0.5
        blink.autoreverses = true
        blink.repeatCount = .infinity
        blink.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        if !UIAccessibility.isReduceMotionEnabled { caret.layer.add(blink, forKey: "blink") }
        clear.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        clear.tintColor = ink.withAlphaComponent(0.5)
        clear.accessibilityLabel = "Clear emoji search"
        clear.addAction(UIAction { [weak self] _ in self?.onClear() }, for: .touchUpInside)
        field.addSubview(clear)

        results.showsHorizontalScrollIndicator = false
        results.contentInsetAdjustmentBehavior = .never
        results.accessibilityLabel = "Emoji results"
        addSubview(results)
        empty.text = "No emoji found"
        empty.font = .systemFont(ofSize: 14)
        empty.textColor = ink.withAlphaComponent(0.6)
        empty.textAlignment = .center
        addSubview(empty)
    }

    required init?(coder: NSCoder) { nil }

    func show(query: String, matches: [String]) {
        text.text = query.isEmpty ? "Search Emoji" : query
        text.textColor = query.isEmpty ? ink.withAlphaComponent(0.5) : ink
        field.accessibilityValue = query
        clear.isHidden = query.isEmpty
        buttons.forEach { $0.removeFromSuperview() }
        buttons = matches.map { emoji in
            let button = UIButton(type: .custom)
            button.setTitle(emoji, for: .normal)
            button.accessibilityLabel = emoji
            button.addAction(UIAction { [weak self] _ in self?.onPick(emoji) }, for: .touchUpInside)
            results.addSubview(button)
            return button
        }
        empty.isHidden = !matches.isEmpty
        results.contentOffset = .zero
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let width = bounds.width
        field.frame = CGRect(x: 0, y: 0, width: width, height: fieldHeight)
        field.layer.cornerRadius = fieldHeight / 2
        icon.frame = CGRect(x: 12, y: (fieldHeight - 20) / 2, width: 20, height: 20)
        clear.frame = CGRect(x: width - fieldHeight, y: 0, width: fieldHeight, height: fieldHeight)
        let textX = icon.frame.maxX + 8
        let textWidth = min(text.intrinsicContentSize.width, clear.frame.minX - textX - 4)
        let queryEmpty = clear.isHidden
        // The caret sits before the placeholder, after typed text.
        text.frame = CGRect(x: queryEmpty ? textX + 4 : textX, y: 0, width: max(0, textWidth), height: fieldHeight)
        caret.frame = CGRect(x: queryEmpty ? textX : text.frame.maxX + 1, y: fieldHeight * 0.22, width: 2, height: fieldHeight * 0.56)

        let rowY = fieldHeight + 4
        let rowHeight = max(1, bounds.height - rowY)
        results.frame = CGRect(x: 0, y: rowY, width: width, height: rowHeight)
        empty.frame = results.frame
        let cellWidth = min(57, max(41, floor((width - 12) / 7)))
        let font = UIFont.systemFont(ofSize: min(35, rowHeight * 0.76))
        for (index, button) in buttons.enumerated() {
            button.titleLabel?.font = font
            button.frame = CGRect(x: CGFloat(index) * cellWidth, y: 0, width: cellWidth, height: rowHeight)
        }
        results.contentSize = CGSize(width: CGFloat(buttons.count) * cellWidth, height: rowHeight)
    }
}
