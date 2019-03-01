// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
import {XYChartManager} from "./core"
import * as am4charts from "@amcharts/amcharts4/charts"
import * as am4core from "@amcharts/amcharts4/core"
import {computeLevels, disableGridButKeepBorderLines, TimeLineItem} from "./timeLineChartHelper"
import {DataManager, InputData} from "./data"

export class TimelineChartManager extends XYChartManager {
  private maxRowIndex = 0

  constructor(container: HTMLElement) {
    super(container, module.hot)

    this.configureDurationAxis()
    const levelAxis = this.configureLevelAxis()
    this.configureSeries()
    this.addHeightAdjuster(levelAxis)
  }

  private configureLevelAxis() {
    const levelAxis = this.chart.yAxes.push(new am4charts.CategoryAxis())
    levelAxis.dataFields.category = "rowIndex"
    levelAxis.renderer.grid.template.location = 0
    levelAxis.renderer.minGridDistance = 1
    disableGridButKeepBorderLines(levelAxis)
    levelAxis.renderer.labels.template.disabled = true
    // level is is internal property - not interested for user
    levelAxis.cursorTooltipEnabled = false
    return levelAxis
  }

  private configureDurationAxis() {
    const durationAxis = this.chart.xAxes.push(new am4charts.DurationAxis())
    durationAxis.durationFormatter.baseUnit = "millisecond"
    durationAxis.durationFormatter.durationFormat = "S"
    durationAxis.min = 0
    durationAxis.strictMinMax = true

    // cursor tooltip is distracting
    durationAxis.cursorTooltipEnabled = false
  }

  private configureSeries() {
    const series = this.chart.series.push(new am4charts.ColumnSeries())
    // series.columns.template.width = am4core.percent(80)
    // https://github.com/amcharts/amcharts4/issues/989#issuecomment-467862120
    series.columns.template.tooltipText = "{name}: {duration}\nlevel: {level}\nrange: {start}-{end}"
    series.columns.template.adapter.add("tooltipText", (value, target, _key) => {
      const dataItem = target.dataItem
      const index = dataItem == null ? -1 : dataItem.index
      const data = this.chart.data
      const item = index >= 0 && index < data.length ? data[index] as TimeLineItem : null
      if (item == null || item.description == null || item.description.length === 0) {
        return value
      }
      else {
        return `${value}\n{description}`
      }
    })
    series.dataFields.openDateX = "start"
    series.dataFields.openValueX = "start"
    series.dataFields.dateX = "end"
    series.dataFields.valueX = "end"
    series.dataFields.categoryY = "rowIndex"

    series.columns.template.propertyFields.fill = "color"
    series.columns.template.propertyFields.stroke = "color"
    // series.columns.template.strokeOpacity = 1

    const valueLabel = series.bullets.push(new am4charts.LabelBullet())
    valueLabel.label.text = "{name}"
    valueLabel.label.truncate = false
    valueLabel.label.hideOversized = false
    valueLabel.label.horizontalCenter = "left"
    // valueLabel.label.fill = am4core.color("#fff")
    valueLabel.locationX = 1
    // https://github.com/amcharts/amcharts4/issues/668#issuecomment-446655416
    valueLabel.interactionsEnabled = false
    // valueLabel.label.fontSize = 12
  }

  private addHeightAdjuster(levelAxis: am4charts.Axis) {
    // https://www.amcharts.com/docs/v4/tutorials/auto-adjusting-chart-height-based-on-a-number-of-data-items/
    // noinspection SpellCheckingInspection
    this.chart.events.on("datavalidated", () => {
      const chart = this.chart
      const targetHeight = chart.pixelHeight + ((this.maxRowIndex + 1) * 30 - levelAxis.pixelHeight)
      chart.svgContainer!!.htmlElement.style.height = targetHeight + "px"
    })
  }

  render(data: DataManager) {
    this.chart.data = this.transformIjData(data.data)

    const originalItems = data.data.items
    const durationAxis = this.chart.xAxes.getIndex(0) as am4charts.DurationAxis
    durationAxis.max = originalItems[originalItems.length - 1].end
  }

  private transformIjData(input: InputData): Array<any> {
    const colorSet = new am4core.ColorSet()
    const transformedItems = new Array<any>(input.items.length)
    computeLevels(input.items)

    // we cannot use actual level as row index because in this case labels will be overlapped, so,
    // row index simply incremented till empirical limit.
    let rowIndex = 0
    this.maxRowIndex = 0
    for (let i = 0; i < input.items.length; i++) {
      const item = input.items[i] as TimeLineItem
      if (rowIndex > 5 && item.level === 0) {
        rowIndex = 0
      }
      else if (rowIndex > this.maxRowIndex) {
        this.maxRowIndex = rowIndex
      }

      const result: any = {
        ...item,
        rowIndex: rowIndex++,
        color: colorSet.getIndex(item.colorIndex),
      }

      transformedItems[i] = result
    }

    transformedItems.sort((a, b) => a.rowIndex - b.rowIndex)
    return transformedItems
  }
}
