package com.intellij.json.surroundWith;

import com.intellij.json.JsonElementTypes;
import com.intellij.json.psi.JsonProperty;
import com.intellij.json.psi.JsonValue;
import com.intellij.lang.surroundWith.SurroundDescriptor;
import com.intellij.lang.surroundWith.Surrounder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author Mikhail Golubev
 */
public class JsonSurroundDescriptor implements SurroundDescriptor {
  private static final Surrounder[] ourSurrounders = new Surrounder[]{
    new JsonWithObjectLiteralSurrounder(),
    new JsonWithArrayLiteralSurrounder(),
    new JsonWithQuotesSurrounder()
  };

  @NotNull
  @Override
  public PsiElement[] getElementsToSurround(PsiFile file, int startOffset, int endOffset) {
    PsiElement firstElement = file.findElementAt(startOffset);
    PsiElement lastElement = file.findElementAt(endOffset - 1);

    // Extend selection beyond possible delimiters
    while (firstElement != null &&
           (firstElement instanceof PsiWhiteSpace || firstElement.getNode().getElementType() == JsonElementTypes.COMMA)) {
      firstElement = firstElement.getNextSibling();
    }
    while (lastElement != null &&
           (lastElement instanceof PsiWhiteSpace || lastElement.getNode().getElementType() == JsonElementTypes.COMMA)) {
      lastElement = lastElement.getPrevSibling();
    }
    if (firstElement != null) {
      startOffset = firstElement.getTextRange().getStartOffset();
    }
    if (lastElement != null) {
      endOffset = lastElement.getTextRange().getEndOffset();
    }

    final JsonProperty property = PsiTreeUtil.findElementOfClassAtRange(file, startOffset, endOffset, JsonProperty.class);
    if (property != null) {
      return collectElements(endOffset, property, JsonProperty.class);
    }

    final JsonValue value = PsiTreeUtil.findElementOfClassAtRange(file, startOffset, endOffset, JsonValue.class);
    if (value != null) {
      return collectElements(endOffset, value, JsonValue.class);
    }
    return PsiElement.EMPTY_ARRAY;
  }

  @NotNull
  private static <T extends PsiElement> PsiElement[] collectElements(int endOffset, @NotNull T property, @NotNull Class<T> kind) {
    final List<T> properties = ContainerUtil.newArrayList(property);
    PsiElement nextSibling = property.getNextSibling();
    while (nextSibling != null && nextSibling.getTextRange().getEndOffset() <= endOffset) {
      if (kind.isInstance(nextSibling)) {
        properties.add(kind.cast(nextSibling));
      }
      nextSibling = nextSibling.getNextSibling();
    }
    return properties.toArray(PsiElement.EMPTY_ARRAY);
  }

  @NotNull
  @Override
  public Surrounder[] getSurrounders() {
    return ourSurrounders;
  }

  @Override
  public boolean isExclusive() {
    return false;
  }
}
