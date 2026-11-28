package chat.papo.mobile.ui.call

/** Quantas colunas a grade usa e a largura de cada quadro, na mesma unidade da área recebida. */
internal data class GridFit(val columns: Int, val tileWidth: Float)

/**
 * Escolhe o número de colunas que deixa os [count] quadros, todos na proporção [aspect] (largura/altura), o maior possível
 * sem passar da área [width] x [height]: nenhum quadro fica fora e não há rolagem. [gap] é o espaço entre quadros. Entre
 * opções do mesmo tamanho fica a de menos colunas.
 */
internal fun fitGrid(count: Int, width: Float, height: Float, aspect: Float, gap: Float): GridFit {
    if (count <= 0 || width <= 0f || height <= 0f || aspect <= 0f) return GridFit(1, 0f)
    var best = GridFit(1, 0f)
    for (columns in 1..count) {
        val rows = (count + columns - 1) / columns
        val byWidth = (width - gap * (columns - 1)) / columns
        val byHeight = (height - gap * (rows - 1)) / rows * aspect
        val tile = minOf(byWidth, byHeight)
        if (tile > best.tileWidth) best = GridFit(columns, tile)
    }
    return best
}
