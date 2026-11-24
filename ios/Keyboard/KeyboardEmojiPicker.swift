import UIKit

final class KeyboardEmojiPicker: UIView {
    private struct Catalog: Decodable {
        let order: [String]
        let groups: [String: [String]]
    }

    private static let catalog: Catalog = {
        guard let url = Bundle(for: KeyboardEmojiPicker.self).url(forResource: "emoji", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let catalog = try? JSONDecoder().decode(Catalog.self, from: data) else {
            return Catalog(order: [], groups: [:])
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

    private let tabs = UIScrollView()
    private let heading = UILabel()
    private let emptyRecents = UILabel()
    private let gridLayout = UICollectionViewFlowLayout()
    private lazy var grid = UICollectionView(frame: .zero, collectionViewLayout: gridLayout)
    private let accent: UIColor
    private let onInsert: (String) -> Void
    private var category: String?
    private var categoryButtons: [UIButton] = []
    private var emoji: [String] = []

    init(frame: CGRect, accent: UIColor, ink: UIColor, onInsert: @escaping (String) -> Void) {
        self.accent = accent
        self.onInsert = onInsert
        super.init(frame: frame)

        heading.font = .systemFont(ofSize: 12, weight: .semibold)
        heading.textColor = ink.withAlphaComponent(0.7)
        heading.accessibilityTraits = .header
        addSubview(heading)

        emptyRecents.text = "Recently used emoji appear here"
        emptyRecents.textColor = ink.withAlphaComponent(0.7)
        emptyRecents.font = .systemFont(ofSize: 14)
        emptyRecents.textAlignment = .center
        emptyRecents.numberOfLines = 0

        tabs.showsHorizontalScrollIndicator = false
        addSubview(tabs)
        gridLayout.scrollDirection = .horizontal
        gridLayout.minimumInteritemSpacing = 4
        gridLayout.minimumLineSpacing = 4
        grid.register(KeyboardEmojiCell.self, forCellWithReuseIdentifier: "emoji")
        grid.dataSource = self
        grid.delegate = self
        grid.backgroundColor = .clear
        grid.showsHorizontalScrollIndicator = false
        grid.alwaysBounceHorizontal = true
        grid.accessibilityLabel = "Emoji picker"
        addSubview(grid)
        addSubview(emptyRecents)

        let categories = ["Recent"] + Self.catalog.order
        for name in categories {
            let button = UIButton(type: .custom)
            let symbol = Self.categoryIcons[name] ?? "circle"
            button.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 17, weight: .medium)), for: .normal)
            button.tintColor = ink.withAlphaComponent(0.8)
            button.accessibilityLabel = name
            button.addAction(UIAction { [weak self] _ in self?.selectCategory(name) }, for: .touchUpInside)
            button.layer.cornerRadius = 9
            tabs.addSubview(button)
            categoryButtons.append(button)
        }
        selectCategory(recentEmoji.isEmpty ? (Self.catalog.order.first ?? "Recent") : "Recent")
    }

    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        let width = bounds.width
        guard width > 0 else { return }
        let tabsHeight: CGFloat = 39
        let headerHeight: CGFloat = 22
        let gridHeight = max(1, bounds.height - tabsHeight - headerHeight - 4)
        heading.frame = CGRect(x: 8, y: 0, width: width - 16, height: headerHeight)
        grid.frame = CGRect(x: 0, y: headerHeight, width: width, height: gridHeight)
        emptyRecents.frame = grid.frame.insetBy(dx: 12, dy: 0)
        tabs.frame = CGRect(x: 0, y: bounds.height - tabsHeight, width: width, height: tabsHeight)

        let tabWidth = max(37, width / CGFloat(max(1, categoryButtons.count)))
        for (index, button) in categoryButtons.enumerated() {
            button.frame = CGRect(x: CGFloat(index) * tabWidth, y: 3, width: tabWidth, height: tabsHeight - 6)
        }
        tabs.contentSize = CGSize(width: CGFloat(categoryButtons.count) * tabWidth, height: tabsHeight)
        if let selected = categoryButtons.first(where: { $0.accessibilityLabel == category }) {
            tabs.scrollRectToVisible(selected.frame, animated: false)
        }

        let rowCount = max(1, Int((gridHeight + 4) / 39))
        let cellHeight = floor((gridHeight - CGFloat(rowCount - 1) * 4) / CGFloat(rowCount))
        let columns = max(1, Int((width + 4) / 42))
        let itemSize = CGSize(width: floor((width - CGFloat(columns - 1) * 4) / CGFloat(columns)), height: cellHeight)
        if gridLayout.itemSize != itemSize { gridLayout.itemSize = itemSize }
    }

    private var recentEmoji: [String] {
        UserDefaults.standard.stringArray(forKey: Self.recentsKey) ?? []
    }

    private func selectCategory(_ name: String) {
        category = name
        heading.text = name == "Recent" ? "Recently used" : name
        emoji = name == "Recent" ? recentEmoji : Self.catalog.groups[name, default: []]
        emptyRecents.isHidden = name != "Recent" || !emoji.isEmpty
        for button in categoryButtons {
            let selected = button.accessibilityLabel == name
            button.backgroundColor = selected ? accent.withAlphaComponent(0.2) : .clear
            button.accessibilityTraits = selected ? [.button, .selected] : .button
        }
        grid.reloadData()
        grid.setContentOffset(.zero, animated: false)
        if let selected = categoryButtons.first(where: { $0.accessibilityLabel == name }) {
            tabs.scrollRectToVisible(selected.frame, animated: !UIAccessibility.isReduceMotionEnabled)
        }
    }
}

extension KeyboardEmojiPicker: UICollectionViewDataSource, UICollectionViewDelegate {
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        emoji.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "emoji", for: indexPath)
        let value = emoji.indices.contains(indexPath.item) ? emoji[indexPath.item] : nil
        (cell as? KeyboardEmojiCell)?.label.text = value
        cell.accessibilityLabel = value
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        guard emoji.indices.contains(indexPath.item) else { return }
        let value = emoji[indexPath.item]
        let recents = [value] + recentEmoji.filter { $0 != value }
        UserDefaults.standard.set(Array(recents.prefix(30)), forKey: Self.recentsKey)
        onInsert(value)
        collectionView.deselectItem(at: indexPath, animated: false)
    }
}

private final class KeyboardEmojiCell: UICollectionViewCell {
    let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        label.frame = contentView.bounds
        label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        label.font = .systemFont(ofSize: 27)
        label.textAlignment = .center
        label.isAccessibilityElement = false
        contentView.addSubview(label)
        isAccessibilityElement = true
        accessibilityTraits = .button
    }

    required init?(coder: NSCoder) { nil }
}
