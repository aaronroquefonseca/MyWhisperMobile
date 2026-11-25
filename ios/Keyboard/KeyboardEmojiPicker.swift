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
    private let clearSearch = UIButton(type: .system)
    private let searchKeys = UIStackView()
    private var searching = false
    private var query = ""
    private var matches: [String] = []
    private let gridLayout = UICollectionViewFlowLayout()
    private lazy var grid = UICollectionView(frame: .zero, collectionViewLayout: gridLayout)
    private let accent: UIColor
    private let ink: UIColor
    private let footerHeight: CGFloat
    private let letters: UIButton
    private let delete: UIButton
    private let globe: UIButton?
    private let onInsert: (String) -> Void
    private let categories = [KeyboardEmojiPicker.recentCategory] + KeyboardEmojiPicker.catalog.order
    private var recents = UserDefaults.standard.stringArray(forKey: KeyboardEmojiPicker.recentsKey) ?? []
    private var categoryButtons: [UIButton] = []
    private var selectedSection: Int?
    private var initialSection: Int?

    init(
        frame: CGRect, accent: UIColor, ink: UIColor, footerHeight: CGFloat,
        letters: UIButton, delete: UIButton, globe: UIButton?, onInsert: @escaping (String) -> Void
    ) {
        self.accent = accent
        self.ink = ink
        self.footerHeight = footerHeight
        self.letters = letters
        self.delete = delete
        self.globe = globe
        self.onInsert = onInsert
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
        searchBar.addAction(UIAction { [weak self] _ in self?.beginSearch() }, for: .touchUpInside)
        addSubview(searchBar)

        clearSearch.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        clearSearch.tintColor = ink.withAlphaComponent(0.7)
        clearSearch.accessibilityLabel = "Clear emoji search"
        clearSearch.isHidden = true
        clearSearch.addAction(UIAction { [weak self] _ in self?.updateQuery("") }, for: .touchUpInside)
        addSubview(clearSearch)

        searchKeys.axis = .vertical
        searchKeys.spacing = 4
        searchKeys.distribution = .fillEqually
        searchKeys.isHidden = true
        let letterRows = ["qwertyuiop", "asdfghjklñ", "zxcvbnm"]
        let searchRows = letterRows.map { row in row.map { String($0) } } + [["Done", "Space", "Delete"]]
        for labels in searchRows {
            let row = UIStackView()
            row.axis = .horizontal
            row.spacing = 3
            row.distribution = .fillEqually
            for label in labels {
                let key = UIButton(type: .system)
                key.setTitle(label, for: .normal)
                key.setTitleColor(ink, for: .normal)
                key.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
                key.titleLabel?.adjustsFontSizeToFitWidth = true
                key.backgroundColor = ink.withAlphaComponent(0.16)
                key.layer.cornerRadius = 6
                key.addAction(UIAction { [weak self] _ in self?.searchKey(label) }, for: .touchUpInside)
                row.addArrangedSubview(key)
            }
            searchKeys.addArrangedSubview(row)
        }
        addSubview(searchKeys)

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
        let categoryHeight: CGFloat = searching ? 0 : 22
        let gridTop = searchHeight + categoryHeight + 3
        let searchKeysHeight: CGFloat = footerHeight < 40 ? 98 : 128
        let footerY = bounds.height - footerHeight
        let gridBottom = searching ? bounds.height - searchKeysHeight : footerY
        let gridHeight = max(1, gridBottom - gridTop - 6)
        searchBar.frame = CGRect(x: 4, y: 0, width: width - 8, height: searchHeight)
        searchBar.layer.cornerRadius = searchHeight / 2
        clearSearch.frame = CGRect(x: width - 44, y: 0, width: 40, height: searchHeight)
        heading.frame = CGRect(x: 8, y: searchHeight, width: width - 16, height: categoryHeight)
        grid.frame = CGRect(x: 0, y: gridTop, width: width, height: gridHeight)
        searchKeys.frame = CGRect(x: 0, y: bounds.height - searchKeysHeight,
                                  width: width, height: searchKeysHeight)

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

    static func searchEmoji(matching query: String) -> [String] {
        let words = query.split(whereSeparator: \.isWhitespace)
        guard !words.isEmpty, let search = catalog.search else { return [] }
        return catalog.order.lazy.flatMap { catalog.groups[$0, default: []] }.filter { emoji in
            let key = emoji.replacingOccurrences(of: "\u{FE0F}", with: "")
            guard let annotation = search[key] else { return false }
            return words.allSatisfy { annotation.localizedStandardContains(String($0)) }
        }.prefix(160).map { $0 }
    }

    private func beginSearch() {
        guard !searching else { return }
        searching = true
        setSearchControlsVisible(true)
        updateQuery("")
    }

    private func endSearch() {
        searching = false
        query = ""
        matches = []
        setSearchControlsVisible(false)
        searchBar.configuration?.title = "Search Emoji"
        searchBar.accessibilityValue = nil
        initialSection = selectedSection ?? 0
        grid.reloadData()
        setNeedsLayout()
    }

    private func setSearchControlsVisible(_ visible: Bool) {
        searchKeys.isHidden = !visible
        heading.isHidden = visible
        tabs.isHidden = visible
        letters.isHidden = visible
        delete.isHidden = visible
        globe?.isHidden = visible
        clearSearch.isHidden = true
    }

    private func updateQuery(_ value: String) {
        query = value
        matches = value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? (recents.isEmpty ? Array(Self.catalog.groups[Self.catalog.order.first ?? "", default: []].prefix(24)) : recents)
            : Self.searchEmoji(matching: value)
        searchBar.configuration?.title = value.isEmpty ? "Search Emoji" : value
        searchBar.accessibilityValue = value
        clearSearch.isHidden = value.isEmpty
        grid.reloadData()
        grid.setContentOffset(.zero, animated: false)
        setNeedsLayout()
    }

    private func searchKey(_ key: String) {
        switch key {
        case "Done": endSearch()
        case "Delete": if !query.isEmpty { updateQuery(String(query.dropLast())) }
        default:
            if query.count < 60 { updateQuery(query + (key == "Space" ? " " : key)) }
        }
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
        if searching { return matches }
        return section == 0 ? recents : Self.catalog.groups[categories[section], default: []]
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
    func numberOfSections(in collectionView: UICollectionView) -> Int { searching ? 1 : categories.count }

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
        guard scrollView === grid, !searching, let item = leadingItem else { return }
        highlightSection(item.section)
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "emoji", for: indexPath)
        if let cell = cell as? KeyboardEmojiCell {
            let values = values(in: indexPath.section)
            let value = values.indices.contains(indexPath.item) ? values[indexPath.item] : nil
            cell.configure(emoji: value, emptyMessage: searching ? "No matching emoji" : "No recent emoji", ink: ink)
        }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        collectionView.deselectItem(at: indexPath, animated: false)
        let values = values(in: indexPath.section)
        guard values.indices.contains(indexPath.item) else { return }
        let value = values[indexPath.item]
        let updated = Array(([value] + recents.filter { $0 != value }).prefix(30))
        if updated != recents {
            let anchor = leadingItem
            let previousOffset = grid.contentOffset.x
            let previousX = anchor.flatMap { gridLayout.layoutAttributesForItem(at: $0)?.frame.minX }
            recents = updated
            UserDefaults.standard.set(recents, forKey: Self.recentsKey)
            if !searching {
                grid.reloadData()
                grid.layoutIfNeeded()
            }
            if !searching, let anchor, let previousX, let currentX = gridLayout.layoutAttributesForItem(at: anchor)?.frame.minX {
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
