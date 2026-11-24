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

    private let tabs = UIScrollView()
    private let gridLayout = UICollectionViewFlowLayout()
    private lazy var grid = UICollectionView(frame: .zero, collectionViewLayout: gridLayout)
    private let accent: UIColor
    private let onInsert: (String) -> Void
    private var category: String?
    private var categoryButtons: [UIButton] = []
    private var emoji: [String] = []

    init(frame: CGRect, accent: UIColor, onInsert: @escaping (String) -> Void) {
        self.accent = accent
        self.onInsert = onInsert
        super.init(frame: frame)

        tabs.showsHorizontalScrollIndicator = false
        addSubview(tabs)
        gridLayout.minimumInteritemSpacing = 4
        gridLayout.minimumLineSpacing = 4
        grid.register(KeyboardEmojiCell.self, forCellWithReuseIdentifier: "emoji")
        grid.dataSource = self
        grid.delegate = self
        grid.backgroundColor = .clear
        grid.accessibilityLabel = "Emoji picker"
        addSubview(grid)

        let categories = (recentEmoji.isEmpty ? [] : ["Recent"]) + Self.catalog.order
        for name in categories {
            let button = UIButton(type: .custom)
            button.setTitle(name == "Recent" ? "🕐" : Self.catalog.groups[name]?.first ?? "?", for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 20)
            button.accessibilityLabel = name
            button.addAction(UIAction { [weak self] _ in self?.selectCategory(name) }, for: .touchUpInside)
            button.layer.cornerRadius = 9
            tabs.addSubview(button)
            categoryButtons.append(button)
        }
        if let first = categories.first { selectCategory(first) }
    }

    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        let width = bounds.width
        guard width > 0 else { return }
        tabs.frame = CGRect(x: 0, y: 0, width: width, height: 34)
        for (index, button) in categoryButtons.enumerated() {
            button.frame = CGRect(x: CGFloat(index) * 38, y: 0, width: 36, height: 34)
        }
        tabs.contentSize = CGSize(width: max(width, CGFloat(categoryButtons.count) * 38), height: 34)
        if let selected = categoryButtons.first(where: { $0.accessibilityLabel == category }) {
            tabs.scrollRectToVisible(selected.frame, animated: false)
        }
        let columns = max(1, Int((width + 4) / 40))
        gridLayout.itemSize = CGSize(width: floor((width - CGFloat(columns - 1) * 4) / CGFloat(columns)), height: 35)
        grid.frame = CGRect(x: 0, y: 38, width: width, height: max(1, bounds.height - 38))
    }

    private var recentEmoji: [String] {
        UserDefaults.standard.stringArray(forKey: Self.recentsKey) ?? []
    }

    private func selectCategory(_ name: String) {
        category = name
        emoji = name == "Recent" ? recentEmoji : Self.catalog.groups[name, default: []]
        for button in categoryButtons {
            let selected = button.accessibilityLabel == name
            button.backgroundColor = selected ? accent.withAlphaComponent(0.2) : .clear
            button.accessibilityTraits = selected ? [.button, .selected] : .button
        }
        grid.reloadData()
        grid.setContentOffset(.zero, animated: false)
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
