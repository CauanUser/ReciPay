"""Gráfico de barras simples desenhado com QPainter (sem bibliotecas extras).

Uma série por gráfico (cor da marca), barras finas com ponta arredondada de 4 px apoiadas
na linha de base, rótulo do valor em cada barra, grade discreta e dica ao passar o mouse.
"""

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QFont, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import QToolTip, QWidget

from recipay import dinheiro
from recipay.ui import tema


def _curto(centavos: int) -> str:
    """R$ 1,2 mil / R$ 950 — rótulo compacto para caber em cima da barra."""
    reais = centavos / 100
    if reais >= 1000:
        return f"R$ {reais / 1000:.1f} mil".replace(".", ",")
    return f"R$ {reais:.0f}"


class GraficoBarras(QWidget):
    def __init__(self, horizontal: bool = False, vazio: str = "Sem dados ainda"):
        super().__init__()
        self.horizontal = horizontal
        self.vazio = vazio
        self.dados: list[tuple[str, int]] = []
        self._areas: list[tuple[QRectF, str, int]] = []
        self.setMouseTracking(True)
        self.setMinimumHeight(190)

    def definir(self, dados: list[tuple[str, int]]) -> None:
        self.dados = dados
        self.update()

    # -------------------------------------------------------------- desenho
    def paintEvent(self, _evento) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        fonte = QFont(self.font())
        p.setFont(fonte)
        self._areas = []
        maximo = max((v for _, v in self.dados), default=0)
        if not self.dados or maximo <= 0:
            p.setPen(QColor(tema.TEXTO_SUAVE))
            p.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, self.vazio)
            return
        if self.horizontal:
            self._horizontal(p, maximo)
        else:
            self._vertical(p, maximo)

    def _barra(self, p: QPainter, r: QRectF, ponta: str) -> None:
        """Retângulo com os dois cantos da ponta arredondados (4 px)."""
        raio = min(4.0, r.width() / 2, r.height() / 2)
        caminho = QPainterPath()
        if ponta == "cima":
            caminho.moveTo(r.left(), r.bottom())
            caminho.lineTo(r.left(), r.top() + raio)
            caminho.quadTo(r.left(), r.top(), r.left() + raio, r.top())
            caminho.lineTo(r.right() - raio, r.top())
            caminho.quadTo(r.right(), r.top(), r.right(), r.top() + raio)
            caminho.lineTo(r.right(), r.bottom())
        else:  # direita
            caminho.moveTo(r.left(), r.top())
            caminho.lineTo(r.right() - raio, r.top())
            caminho.quadTo(r.right(), r.top(), r.right(), r.top() + raio)
            caminho.lineTo(r.right(), r.bottom() - raio)
            caminho.quadTo(r.right(), r.bottom(), r.right() - raio, r.bottom())
            caminho.lineTo(r.left(), r.bottom())
        caminho.closeSubpath()
        p.fillPath(caminho, QColor(tema.AZUL))

    def _vertical(self, p: QPainter, maximo: int) -> None:
        m = p.fontMetrics()
        esq, dir_, topo, base = 8, 8, m.height() + 6, m.height() + 10
        largura = self.width() - esq - dir_
        altura = self.height() - topo - base
        y0 = topo + altura
        # grade discreta (meio e topo) + linha de base
        p.setPen(QPen(QColor(tema.GRADE), 1))
        for frac in (0.5, 1.0):
            y = y0 - altura * frac
            p.drawLine(QPointF(esq, y), QPointF(esq + largura, y))
        p.setPen(QPen(QColor(tema.BORDA), 1))
        p.drawLine(QPointF(esq, y0), QPointF(esq + largura, y0))
        n = len(self.dados)
        passo = largura / n
        larg_barra = min(36.0, passo * 0.5)
        for i, (rotulo, valor) in enumerate(self.dados):
            cx = esq + passo * (i + 0.5)
            h = altura * valor / maximo
            r = QRectF(cx - larg_barra / 2, y0 - h, larg_barra, h)
            if valor > 0:
                self._barra(p, r, "cima")
                p.setPen(QColor(tema.TEXTO))
                p.drawText(QRectF(cx - passo / 2, r.top() - m.height() - 2, passo, m.height()),
                           Qt.AlignmentFlag.AlignCenter, _curto(valor))
            p.setPen(QColor(tema.TEXTO_SUAVE))
            p.drawText(QRectF(cx - passo / 2, y0 + 4, passo, m.height()), Qt.AlignmentFlag.AlignCenter, rotulo)
            self._areas.append((QRectF(cx - passo / 2, topo, passo, altura + base), rotulo, valor))

    def _horizontal(self, p: QPainter, maximo: int) -> None:
        m = p.fontMetrics()
        col_rotulo = min(150, max(m.horizontalAdvance(r) for r, _ in self.dados) + 12)
        col_valor = m.horizontalAdvance("R$ 000,0 mil") + 8
        esq = col_rotulo
        largura = self.width() - esq - col_valor - 8
        n = len(self.dados)
        passo = (self.height() - 8) / max(n, 1)
        alt_barra = min(18.0, passo * 0.55)
        p.setPen(QPen(QColor(tema.BORDA), 1))
        p.drawLine(QPointF(esq, 4), QPointF(esq, self.height() - 4))
        for i, (rotulo, valor) in enumerate(self.dados):
            cy = 4 + passo * (i + 0.5)
            w = largura * valor / maximo
            r = QRectF(esq, cy - alt_barra / 2, w, alt_barra)
            p.setPen(QColor(tema.TEXTO))
            texto = m.elidedText(rotulo, Qt.TextElideMode.ElideRight, col_rotulo - 10)
            p.drawText(QRectF(0, cy - m.height() / 2, col_rotulo - 8, m.height()),
                       Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter, texto)
            if valor > 0:
                self._barra(p, r, "direita")
            p.drawText(QRectF(r.right() + 6, cy - m.height() / 2, col_valor, m.height()),
                       Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter, _curto(valor))
            self._areas.append((QRectF(0, cy - passo / 2, self.width(), passo), rotulo, valor))

    # -------------------------------------------------------------- interação
    def mouseMoveEvent(self, evento) -> None:
        for area, rotulo, valor in self._areas:
            if area.contains(evento.position()):
                QToolTip.showText(evento.globalPosition().toPoint(), f"{rotulo}: {dinheiro.formatar(valor)}", self)
                return
        QToolTip.hideText()
